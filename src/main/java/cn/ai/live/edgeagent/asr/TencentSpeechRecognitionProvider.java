package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.audio.PcmAudioFrame;
import cn.ai.live.edgeagent.audio.AudioDiagnostics;
import cn.ai.live.edgeagent.audio.PcmPacketAggregator;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import cn.ai.live.edgeagent.runtime.RuntimeEventRecorder;
import com.tencent.asrv2.AsrConstant;
import com.tencent.asrv2.SpeechRecognizer;
import com.tencent.asrv2.SpeechRecognizerListener;
import com.tencent.asrv2.SpeechRecognizerRequest;
import com.tencent.asrv2.SpeechRecognizerResponse;
import com.tencent.asrv2.SpeechRecognizerResult;
import com.tencent.core.ws.Credential;
import com.tencent.core.ws.SpeechClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 腾讯云实时语音识别提供者。
 * <p>
 * 使用 WebSocket 协议连接腾讯云 ASR 传统实时识别接口（{@link SpeechRecognizer}），
 * 发送 PCM 音频并接收识别结果。传统模式使用 result_mod=0，文字从
 * {@code response.getResult().getVoiceTextStr()} 提取。
 * </p>
 */
@Slf4j
@Lazy
@Service
public class TencentSpeechRecognitionProvider implements SpeechRecognitionProvider {

    /** 腾讯云 ASR 支持的合法 voice_format 枚举值 */
    private static final Set<Integer> VALID_VOICE_FORMATS = Set.of(1, 4, 6, 8, 10, 12, 14, 16);

    private final AiLiveProperties properties;
    private final AudioDiagnostics diagnostics;
    private final RuntimeEventRecorder recorder;
    private final PcmPacketAggregator aggregator = new PcmPacketAggregator();
    private final List<SpeechRecognitionListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean desiredRunning = new AtomicBoolean(false);
    private final Object lock = new Object();
    private ExecutorService reconnectExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "tencent-asr-reconnect"));
    private ThreadPoolExecutor audioWriteExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(10), r -> new Thread(r, "tencent-asr-audio-writer"));
    private volatile SpeechRecognizer recognizer;
    private volatile SpeechClient speechClient;
    private volatile String status = "STOPPED";
    private volatile Instant lastConnectedAt;
    private volatile String lastError;
    private volatile String lastPartialText;
    private volatile String lastFinalText;
    private volatile Instant lastResultAt;
    private volatile boolean firstAsrWriteLogged;
    private volatile String currentRecognizerId;

    public TencentSpeechRecognitionProvider(AiLiveProperties properties) {
        this(properties, new AudioDiagnostics(), null);
    }

    @Autowired
    public TencentSpeechRecognitionProvider(AiLiveProperties properties, AudioDiagnostics diagnostics,
                                            RuntimeEventRecorder recorder) {
        this.properties = properties;
        this.diagnostics = diagnostics;
        this.recorder = recorder;
    }

    @Override
    public void addListener(SpeechRecognitionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void start() {
        if (!properties.getAsr().getTencent().isEnabled()) {
            desiredRunning.set(false);
            status = SpeechRecognitionStatus.DISABLED.name();
            lastError = null;
            log.info("腾讯云实时语音识别未启用: ai-live.asr.tencent.enabled=false");
            return;
        }
        List<String> missing = missingCredentialVariables();
        if (!missing.isEmpty()) {
            desiredRunning.set(false);
            status = SpeechRecognitionStatus.MISCONFIGURED.name();
            lastError = "缺少环境变量: " + String.join(", ", missing);
            recordRuntimeError("ASR_MISCONFIGURED", lastError);
            log.warn("腾讯云实时语音识别配置不完整: {}", lastError);
            return;
        }
        // 重新创建执行器（stop 可能已将其关闭），支持手动重连场景
        ensureExecutorsReady();
        desiredRunning.set(true);
        status = "CONNECTING";
        connectWithBackoff();
    }

    private void ensureExecutorsReady() {
        if (reconnectExecutor.isShutdown() || reconnectExecutor.isTerminated()) {
            reconnectExecutor = Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "tencent-asr-reconnect"));
        }
        if (audioWriteExecutor.isShutdown() || audioWriteExecutor.isTerminated()) {
            audioWriteExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(10), r -> new Thread(r, "tencent-asr-audio-writer"));
        }
    }

    @Override
    public void sendAudio(PcmAudioFrame frame) {
        acceptPcm(frame.data());
    }

    @Override
    public void acceptPcm(byte[] pcm) {
        SpeechRecognizer current = recognizer;
        if (current == null) {
            if (SpeechRecognitionStatus.MISCONFIGURED.name().equals(status)) {
                diagnostics.recordDroppedAudio(pcm.length);
                aggregator.reset();
                log.debug("ASR 配置不完整，丢弃实时音频: bytes={}", pcm.length);
                return;
            }
            lastError = "ASR recognizer 未就绪，暂未发送音频";
            diagnostics.recordDroppedAudio(pcm.length);
            aggregator.reset();
            if ("CONNECTED".equals(status)) {
                recordRuntimeError("ASR_RECOGNIZER_NULL", lastError);
                log.warn("ASR 已标记连接，但 recognizer 为空，跳过当前音频包");
            } else {
                log.debug("ASR 未连接，暂不发送音频: status={}, bytes={}", status, pcm.length);
            }
            return;
        }
        if (!"CONNECTED".equals(status)) {
            diagnostics.recordDroppedAudio(pcm.length);
            aggregator.reset();
            log.debug("ASR 未连接，丢弃实时音频: status={}, bytes={}", status, pcm.length);
            return;
        }
        aggregator.append(pcm, packet -> submitAsrWrite(current, currentRecognizerId, packet));
    }

    private void submitAsrWrite(SpeechRecognizer current, String recognizerId, byte[] packet) {
        try {
            audioWriteExecutor.execute(() -> writeAggregatedPacket(current, recognizerId, packet));
        } catch (RejectedExecutionException ex) {
            diagnostics.recordDroppedAudio(packet.length);
            recordRuntimeError("ASR_WRITE_QUEUE_FULL", "ASR 写入队列已满，丢弃实时音频包", "TencentSpeechRecognitionProvider");
        }
    }

    private void writeAggregatedPacket(SpeechRecognizer current, String recognizerId, byte[] packet) {
        try {
            current.write(packet);
            diagnostics.recordAsrWrite(packet.length);
            if (!firstAsrWriteLogged) {
                firstAsrWriteLogged = true;
                log.info("[ASR音频] 已发送首个 PCM 数据包: bytes={}, recognizerId={}", packet.length, recognizerId);
            }
        } catch (Exception ex) {
            log.error("发送音频到腾讯云失败，准备重连", ex);
            lastError = ex.getMessage();
            status = "ERROR";
            recordRuntimeError("ASR_WRITE_FAILED", ex.getMessage());
            scheduleReconnect();
        }
    }

    @Override
    public void stop() {
        desiredRunning.set(false);
        closeRecognizer();
        reconnectExecutor.shutdownNow();
        audioWriteExecutor.shutdownNow();
        status = "STOPPED";
        log.info("腾讯云实时语音识别已停止");
    }

    private void connectWithBackoff() {
        reconnectExecutor.submit(() -> {
            int attempt = 0;
            while (desiredRunning.get()) {
                try {
                    createAndStartRecognizer();
                    return;
                } catch (Exception ex) {
                    if (isMisconfigured(ex)) {
                        desiredRunning.set(false);
                        status = SpeechRecognitionStatus.MISCONFIGURED.name();
                        lastError = ex.getMessage();
                        recordRuntimeError("ASR_MISCONFIGURED", lastError);
                        log.warn("腾讯云实时语音识别配置不完整: {}", lastError);
                        return;
                    }
                    long delayMillis = nextDelayMillis(attempt++);
                    lastError = ex.getMessage();
                    status = "ERROR";
                    log.error("连接腾讯云实时语音识别失败，{} ms 后重试", delayMillis, ex);
                    sleep(delayMillis);
                }
            }
        });
    }

    /**
     * 创建并启动识别器。
     * <p>
     * 使用 SDK 传统实时识别类 {@link SpeechRecognizer}，区别于 V2 句子模式识别器。
     * 构造函数签名：
     * {@code SpeechRecognizer(SpeechClient, Credential, SpeechRecognizerRequest, SpeechRecognizerListener)}
     * </p>
     */
    private void createAndStartRecognizer() throws Exception {
        AiLiveProperties.TencentCloud tc = properties.getTencentCloud();
        String appId = requireConfig(tc.getAppId(), "tencent-cloud.app-id");
        String secretId = requireConfig(tc.getSecretId(), "tencent-cloud.secret-id");
        String secretKey = requireConfig(tc.getSecretKey(), "tencent-cloud.secret-key");
        Credential credential = new Credential(appId, secretId, secretKey);

        String engineModelType = properties.getAsr().getEngineModelType();
        validateEngineModelType(engineModelType);

        int voiceFormat = 1; // PCM
        validateVoiceFormat(voiceFormat);

        String voiceId = "ai-live-" + UUID.randomUUID();

        // 使用 SpeechRecognizerRequest 设置所有参数（直接 setter，无需 extra param）
        SpeechRecognizerRequest request = SpeechRecognizerRequest.init();
        request.setEngineModelType(engineModelType);
        request.setVoiceId(voiceId);
        request.setVoiceFormat(voiceFormat);
        request.setNeedVad(1);
        request.setConvertNumMode(1);
        request.setResultMod(0);               // 传统模式
        request.setVadSilenceTime(1000);
        request.setFilterDirty(1);
        request.setFilterModal(1);
        request.setFilterPunc(1);

        logSanitizedParams(appId, engineModelType, voiceFormat, voiceId);

        SpeechClient client = new SpeechClient(AsrConstant.DEFAULT_RT_REQ_URL);
        SpeechRecognizer next = new SpeechRecognizer(client, credential, request, new TencentListener());

        synchronized (lock) {
            closeRecognizer();
            next.start();
            // 竞态防护：连接建立成功后发现用户已断开，立即关闭，避免继续发送音频
            if (!desiredRunning.get()) {
                log.info("[ASR] 连接建立时发现用户已断开，立即关闭识别器");
                next.close();
                client.shutdown();
                return;
            }
            recognizer = next;
            speechClient = client;
            currentRecognizerId = voiceId + "@" + Integer.toHexString(System.identityHashCode(next));
        }
        lastConnectedAt = Instant.now();
        lastError = null;
        firstAsrWriteLogged = false;
        status = "CONNECTED";
        log.info("已连接腾讯云实时语音识别: voiceId={}, engine={}, recognizerId={}", voiceId, engineModelType, currentRecognizerId);
    }

    private void recordRuntimeError(String code, String message) {
        recordRuntimeError(code, message, "TencentSpeechRecognitionProvider");
    }

    private void recordRuntimeError(String code, String message, String component) {
        if (recorder != null) {
            recorder.recordError(code, message, component);
        }
    }

    /**
     * 校验 engine_model_type，不能为空。
     */
    void validateEngineModelType(String engineModelType) {
        if (engineModelType == null || engineModelType.isBlank()) {
            throw new IllegalArgumentException(
                    "engine_model_type 不能为空，当前配置值: " + engineModelType);
        }
    }

    /**
     * 校验 voice_format，必须是 SDK 支持的合法整数值。
     */
    void validateVoiceFormat(int voiceFormat) {
        if (!VALID_VOICE_FORMATS.contains(voiceFormat)) {
            throw new IllegalArgumentException(
                    "voice_format 不合法: " + voiceFormat
                            + "，有效值: " + VALID_VOICE_FORMATS);
        }
    }

    /**
     * 输出脱敏后的请求参数（不含 SecretId、SecretKey、签名）。
     */
    private void logSanitizedParams(String appId, String engineModelType, int voiceFormat, String voiceId) {
        log.info("[ASR请求参数] appId={}, engineModelType={}, voiceFormat={}, resultMod={}, voiceId={}, needVad={}, convertNumMode={}",
                sanitize(appId), engineModelType, voiceFormat, 0, voiceId, 1, 1);
    }

    /**
     * 对敏感值脱敏：保留前 4 位和后 4 位。
     */
    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 8) {
            return value.substring(0, Math.min(3, value.length())) + "***";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private void scheduleReconnect() {
        if (!desiredRunning.get()) {
            log.debug("[ASR] 当前为手动停止状态，不执行自动重连");
            return;
        }
        closeRecognizer();
        connectWithBackoff();
    }

    private void closeRecognizer() {
        synchronized (lock) {
            if (recognizer != null) {
                try {
                    recognizer.close();
                    log.info("腾讯云实时语音识别连接已关闭");
                } catch (Exception ex) {
                    log.warn("关闭腾讯云实时语音识别异常", ex);
                } finally {
                    recognizer = null;
                    currentRecognizerId = null;
                    aggregator.reset();
                }
            }
            if (speechClient != null) {
                try {
                    speechClient.shutdown();
                } catch (Exception ex) {
                    log.warn("关闭 SpeechClient 异常", ex);
                } finally {
                    speechClient = null;
                }
            }
        }
    }

    private long nextDelayMillis(int attempt) {
        List<java.time.Duration> delays = properties.getAsr().getReconnectDelays();
        return delays.get(Math.min(attempt, delays.size() - 1)).toMillis();
    }

    private void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String requireConfig(String value, String configKey) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少配置项: ai-live." + configKey);
        }
        return value;
    }

    private List<String> missingCredentialVariables() {
        AiLiveProperties.TencentCloud tc = properties.getTencentCloud();
        List<String> missing = new ArrayList<>();
        if (tc.getAppId() == null || tc.getAppId().isBlank()) {
            missing.add("TENCENT_ASR_APP_ID");
        }
        if (tc.getSecretId() == null || tc.getSecretId().isBlank()) {
            missing.add("TENCENT_ASR_SECRET_ID");
        }
        if (tc.getSecretKey() == null || tc.getSecretKey().isBlank()) {
            missing.add("TENCENT_ASR_SECRET_KEY");
        }
        return missing;
    }

    private boolean isMisconfigured(Exception ex) {
        return ex instanceof IllegalStateException
                && ex.getMessage() != null
                && ex.getMessage().startsWith("缺少配置项:");
    }

    // ======================== 识别结果提取 ========================

    /**
     * 从传统模式响应中提取文字。
     * 使用 {@code response.getResult().getVoiceTextStr()}。
     */
    private String extractText(SpeechRecognizerResponse response) {
        if (response == null) {
            return null;
        }
        SpeechRecognizerResult result = response.getResult();
        if (result == null) {
            return null;
        }
        String text = result.getVoiceTextStr();
        return (text != null && !text.isBlank()) ? text : null;
    }

    /**
     * 提取 sliceType 诊断值。
     */
    private Integer extractSliceType(SpeechRecognizerResponse response) {
        if (response == null) {
            return null;
        }
        SpeechRecognizerResult result = response.getResult();
        return result != null ? result.getSliceType() : null;
    }

    /**
     * 统一发布识别结果（仅 onSentenceEnd 调用）。
     */
    private void publishFinal(SpeechRecognizerResponse response) {
        String text = extractText(response);
        if (text == null || text.isBlank()) {
            log.info("[ASR][FINAL] 文本为空，跳过发布");
            return;
        }
        lastFinalText = text;
        lastResultAt = Instant.now();
        SpeechRecognitionResult result = new SpeechRecognitionResult(
                text, true, response.getVoiceId(), lastResultAt,
                SpeechRecognitionProviderType.TENCENT, response.getVoiceId(), 0, 0, null);
        listeners.forEach(listener -> listener.onResult(result));
    }

    // ======================== Listener ========================

    private class TencentListener extends SpeechRecognizerListener {

        @Override
        public void onRecognitionStart(SpeechRecognizerResponse response) {
            log.info("[ASR回调] onRecognitionStart voiceId={}, code={}, message={}",
                    response.getVoiceId(), response.getCode(), response.getMessage());
            log.info("腾讯云实时语音识别开始: voiceId={}", response.getVoiceId());
        }

        @Override
        public void onRecognitionResultChange(SpeechRecognizerResponse response) {
            String text = extractText(response);
            Integer sliceType = extractSliceType(response);
            log.info("[ASR][TEMP] onRecognitionResultChange voiceId={}, code={}, message={}, sliceType={}, textEmpty={}, textLength={}",
                    response.getVoiceId(), response.getCode(), response.getMessage(),
                    sliceType,
                    text == null || text.isBlank(),
                    text == null ? 0 : text.length());
            if (text != null && !text.isBlank()) {
                log.info("[ASR][TEMP] text={}", text);
                // 更新最近临时结果
                lastPartialText = text;
                lastResultAt = Instant.now();
            }
            // 临时结果不进入 CommandMatcher，不触发动作
        }

        @Override
        public void onSentenceBegin(SpeechRecognizerResponse response) {
            // 句子开始标记，仅记录日志
            log.debug("[ASR回调] onSentenceBegin voiceId={}", response.getVoiceId());
        }

        @Override
        public void onSentenceEnd(SpeechRecognizerResponse response) {
            String text = extractText(response);
            Integer sliceType = extractSliceType(response);
            log.info("[ASR][FINAL] onSentenceEnd voiceId={}, code={}, message={}, sliceType={}, textEmpty={}, textLength={}",
                    response.getVoiceId(), response.getCode(), response.getMessage(),
                    sliceType,
                    text == null || text.isBlank(),
                    text == null ? 0 : text.length());
            if (text != null && !text.isBlank()) {
                log.info("[ASR][FINAL] text={}", text);
            }
            // 最终结果进入 CommandMatcher
            publishFinal(response);
        }

        @Override
        public void onRecognitionComplete(SpeechRecognizerResponse response) {
            log.info("[ASR回调] onRecognitionComplete voiceId={}, code={}, message={}",
                    response.getVoiceId(), response.getCode(), response.getMessage());
            // 只处理生命周期结束，不重复提交文本
        }

        @Override
        public void onFail(SpeechRecognizerResponse response) {
            log.error("[ASR回调] onFail voiceId={}, code={}, message={}",
                    response == null ? null : response.getVoiceId(),
                    response == null ? null : response.getCode(),
                    response == null ? null : response.getMessage());
            lastError = response == null ? null : response.getMessage();
            status = "ERROR";
            recordRuntimeError("ASR_FAIL", lastError != null ? lastError : "unknown");
            scheduleReconnect();
        }

        @Override
        public void onMessage(SpeechRecognizerResponse response) {
            // 原始消息回调，用于诊断
            if (log.isDebugEnabled()) {
                String text = extractText(response);
                Integer sliceType = extractSliceType(response);
                log.debug("[ASR][RAW] voiceId={}, code={}, message={}, sliceType={}, index={}, text={}, end={}",
                        response.getVoiceId(), response.getCode(), response.getMessage(),
                        sliceType,
                        response.getResult() != null ? response.getResult().getIndex() : null,
                        text,
                        response.getEnd());
            }
        }
    }

    // ======================== 公开诊断方法 ========================

    public String status() {
        return status;
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public SpeechRecognitionProviderType getProviderType() {
        return SpeechRecognitionProviderType.TENCENT;
    }

    @Override
    public boolean isReady() {
        return "CONNECTED".equals(status);
    }

    public Instant lastConnectedAt() {
        return lastConnectedAt;
    }

    public String lastError() {
        return lastError;
    }

    public long asrBytesSent() {
        return diagnostics.asrBytesSent();
    }

    public long asrPacketsSent() {
        return diagnostics.asrPacketsSent();
    }

    public Instant lastAsrWriteAt() {
        return diagnostics.lastAsrWriteAt();
    }

    public long droppedAudioBytes() {
        return diagnostics.droppedAudioBytes();
    }

    public String currentRecognizerId() {
        return currentRecognizerId;
    }

    public String lastPartialText() {
        return lastPartialText;
    }

    public String lastFinalText() {
        return lastFinalText;
    }

    public Instant lastResultAt() {
        return lastResultAt;
    }
}
