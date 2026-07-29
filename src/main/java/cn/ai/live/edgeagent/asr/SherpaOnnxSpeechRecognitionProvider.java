package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.audio.PcmAudioFrame;
import cn.ai.live.edgeagent.config.AiLivePathResolver;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import cn.ai.live.edgeagent.runtime.RuntimeEventRecorder;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Slf4j
@Lazy
@Component
public class SherpaOnnxSpeechRecognitionProvider implements SpeechRecognitionProvider {
    private static final String NATIVE_JAR_NAME = "sherpa-onnx-native-lib-win-x64-v1.12.10.jar";

    private final AiLiveProperties properties;
    private final AiLivePathResolver pathResolver;
    private final Pcm16ToFloatConverter converter;
    private final LocalAsrDiagnostics diagnostics;
    private final RuntimeEventRecorder recorder;
    private final List<SpeechRecognitionListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();
    private ArrayBlockingQueue<byte[]> queue;
    private ExecutorService decodeExecutor;
    private SherpaOnnxRuntimeEngine engine;
    private volatile SpeechRecognitionStatus status = SpeechRecognitionStatus.STOPPED;
    private volatile String sessionId = UUID.randomUUID().toString();
    private volatile String lastPartial = "";

    public SherpaOnnxSpeechRecognitionProvider(AiLiveProperties properties, AiLivePathResolver pathResolver,
                                               Pcm16ToFloatConverter converter, LocalAsrDiagnostics diagnostics,
                                               RuntimeEventRecorder recorder) {
        this.properties = properties;
        this.pathResolver = pathResolver;
        this.converter = converter;
        this.diagnostics = diagnostics;
        this.recorder = recorder;
    }

    @Override
    public void addListener(SpeechRecognitionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public synchronized void start() {
        stop();
        status = SpeechRecognitionStatus.STARTING;
        AiLiveProperties.Sherpa sherpa = properties.getAsr().getSherpa();
        queue = new ArrayBlockingQueue<>(sherpa.getQueueCapacity());
        diagnostics.accepted(0, 0, sherpa.getQueueCapacity());

        Path modelRoot = validateModel(sherpa);
        NativePaths nativePaths = validateNative(sherpa);
        if (modelRoot == null || nativePaths == null) {
            return;
        }
        try {
            engine = new SherpaOnnxRuntimeEngine(sherpa, modelRoot, nativePaths.apiJar(), nativePaths.nativeJar());
        } catch (RuntimeException ex) {
            status = SpeechRecognitionStatus.NATIVE_LIBRARY_ERROR;
            diagnostics.modelStatus(status.name());
            diagnostics.error(safeMessage(ex));
            recorder.recordError("LOCAL_ASR_NATIVE_ERROR", safeMessage(ex), "SherpaOnnxSpeechRecognitionProvider");
            closeEngine();
            return;
        }

        accepting.set(true);
        status = SpeechRecognitionStatus.READY;
        diagnostics.modelStatus(status.name());
        decodeExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "sherpa-onnx-decoder"));
        decodeExecutor.submit(this::decodeLoop);
        log.info("Sherpa-ONNX 本地离线识别已就绪: model={}, version={}",
                diagnostics.modelName(), diagnostics.modelVersion());
    }

    private NativePaths validateNative(AiLiveProperties.Sherpa sherpa) {
        Path nativeRoot = controlledPath(sherpa.getNativeRoot());
        Path apiJar = controlledPath(sherpa.getJarPath());
        Path nativeJar = nativeRoot == null ? null : nativeRoot.resolve(NATIVE_JAR_NAME).normalize();
        if (nativeRoot == null || apiJar == null || !Files.isDirectory(nativeRoot)
                || !Files.isRegularFile(apiJar) || nativeJar == null || !nativeJar.startsWith(nativeRoot)
                || !Files.isRegularFile(nativeJar)) {
            status = SpeechRecognitionStatus.NATIVE_LIBRARY_ERROR;
            diagnostics.modelStatus(status.name());
            diagnostics.error("Sherpa-ONNX Java API JAR 或 Windows x64 native-lib JAR 缺失。");
            return null;
        }
        return new NativePaths(apiJar, nativeJar);
    }

    private Path validateModel(AiLiveProperties.Sherpa sherpa) {
        Path root = controlledPath(sherpa.getModelRoot());
        if (root == null || !Files.isDirectory(root)) {
            status = SpeechRecognitionStatus.MODEL_MISSING;
            diagnostics.modelStatus(status.name());
            diagnostics.error("Sherpa-ONNX 模型目录不存在。");
            return null;
        }
        for (String file : List.of(sherpa.getEncoder(), sherpa.getDecoder(), sherpa.getTokens())) {
            Path candidate = root.resolve(file).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                status = SpeechRecognitionStatus.MODEL_MISSING;
                diagnostics.modelStatus(status.name());
                diagnostics.error("Sherpa-ONNX 模型文件缺失: " + file);
                return null;
            }
            try {
                if (Files.size(candidate) <= 0) {
                    status = SpeechRecognitionStatus.MODEL_MISSING;
                    diagnostics.modelStatus(status.name());
                    diagnostics.error("Sherpa-ONNX 模型文件为空: " + file);
                    return null;
                }
            } catch (Exception ex) {
                status = SpeechRecognitionStatus.MODEL_MISSING;
                diagnostics.modelStatus(status.name());
                diagnostics.error(safeMessage(ex));
                return null;
            }
        }
        return root;
    }

    private Path controlledPath(String value) {
        try {
            Path configured = Path.of(value);
            if (configured.isAbsolute()) {
                return configured.normalize();
            }
            Path base = pathResolver.baseDirectory();
            Path path = pathResolver.resolve(value);
            return path.startsWith(base) ? path : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private void decodeLoop() {
        while (accepting.get() || (queue != null && !queue.isEmpty())) {
            try {
                byte[] pcm = queue.poll(200, TimeUnit.MILLISECONDS);
                if (pcm == null) {
                    continue;
                }
                float[] samples = converter.toFloatSamples(pcm);
                SherpaOnnxRuntimeEngine.RecognitionTick tick = engine.acceptAndDecode(samples, lastPartial);
                diagnostics.decoded();
                if (tick.partialChanged()) {
                    publishPartial(tick.text());
                }
                if (tick.finalResult()) {
                    publishFinal(tick.text());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                status = SpeechRecognitionStatus.ERROR;
                diagnostics.modelStatus(status.name());
                diagnostics.error(safeMessage(ex));
                recorder.recordError("LOCAL_ASR_DECODE_ERROR", safeMessage(ex), "SherpaOnnxSpeechRecognitionProvider");
            }
        }
        log.info("Sherpa-ONNX 解码线程已退出");
    }

    @Override
    public void sendAudio(PcmAudioFrame frame) {
        acceptPcm(frame.data());
    }

    @Override
    public void acceptPcm(byte[] pcm) {
        if (!accepting.get() || queue == null || pcm == null) {
            diagnostics.dropped(pcm == null ? 0 : pcm.length, 0, properties.getAsr().getSherpa().getQueueCapacity());
            return;
        }
        if (!queue.offer(pcm.clone())) {
            byte[] dropped = queue.poll();
            diagnostics.dropped(dropped == null ? pcm.length : dropped.length, queue.size(), queue.remainingCapacity() + queue.size());
            queue.offer(pcm.clone());
        }
        diagnostics.accepted(pcm.length, queue.size(), queue.remainingCapacity() + queue.size());
    }

    public String recognizePcm16k(byte[] pcm) {
        if (!isReady() || engine == null || pcm == null || pcm.length == 0) {
            return "";
        }
        float[] samples = converter.toFloatSamples(pcm);
        return engine.recognizeSamples(samples, properties.getAsr().getSherpa().getSampleRate());
    }

    void publishPartialForTest(String text) {
        publishPartial(text);
    }

    void publishFinalForTest(String text) {
        publishFinal(text);
    }

    private void publishPartial(String text) {
        if (text == null || text.isBlank() || text.equals(lastPartial)) {
            return;
        }
        lastPartial = text;
        diagnostics.partial(text);
        listeners.forEach(listener -> listener.onResult(new SpeechRecognitionResult(text, false, sessionId,
                Instant.now(), SpeechRecognitionProviderType.SHERPA_ONNX, sessionId, sequence.incrementAndGet(), 0, null)));
    }

    private void publishFinal(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        diagnostics.finalResult(text);
        listeners.forEach(listener -> listener.onResult(new SpeechRecognitionResult(text, true, sessionId,
                Instant.now(), SpeechRecognitionProviderType.SHERPA_ONNX, sessionId, sequence.incrementAndGet(), 0, null)));
        sessionId = UUID.randomUUID().toString();
        lastPartial = "";
    }

    @Override
    public synchronized void stop() {
        accepting.set(false);
        if (decodeExecutor != null) {
            decodeExecutor.shutdownNow();
            decodeExecutor = null;
        }
        closeEngine();
        if (status == SpeechRecognitionStatus.READY || status == SpeechRecognitionStatus.STARTING
                || status == SpeechRecognitionStatus.ERROR) {
            status = SpeechRecognitionStatus.STOPPED;
            diagnostics.modelStatus(status.name());
        }
    }

    @PreDestroy
    @Override
    public void close() {
        stop();
    }

    private void closeEngine() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception ex) {
                diagnostics.error(safeMessage(ex));
            } finally {
                engine = null;
            }
        }
    }

    private String safeMessage(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    @Override
    public String getStatus() {
        return status.name();
    }

    @Override
    public SpeechRecognitionProviderType getProviderType() {
        return SpeechRecognitionProviderType.SHERPA_ONNX;
    }

    @Override
    public boolean isReady() {
        return status == SpeechRecognitionStatus.READY;
    }

    private record NativePaths(Path apiJar, Path nativeJar) {
    }
}
