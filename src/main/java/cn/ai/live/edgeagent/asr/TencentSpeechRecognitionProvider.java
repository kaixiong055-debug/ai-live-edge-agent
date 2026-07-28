package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.audio.PcmAudioFrame;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import com.tencent.asrv2.RealtimeRecognitionListenerV2;
import com.tencent.asrv2.RealtimeRecognitionResponseV2;
import com.tencent.asrv2.RealtimeRecognizerV2;
import com.tencent.asrv2.SentenceItem;
import com.tencent.asrv2.SentenceResult;
import com.tencent.core.ws.Credential;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TencentSpeechRecognitionProvider implements SpeechRecognitionProvider {

    private final AiLiveProperties properties;
    private final List<SpeechRecognitionListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean desiredRunning = new AtomicBoolean(false);
    private final Object lock = new Object();
    private final ExecutorService reconnectExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "tencent-asr-reconnect"));
    private volatile RealtimeRecognizerV2 recognizer;

    public TencentSpeechRecognitionProvider(AiLiveProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addListener(SpeechRecognitionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void start() {
        desiredRunning.set(true);
        connectWithBackoff();
    }

    @Override
    public void sendAudio(PcmAudioFrame frame) {
        RealtimeRecognizerV2 current = recognizer;
        if (current == null) {
            return;
        }
        try {
            current.write(frame.data());
        } catch (Exception ex) {
            log.error("发送音频到腾讯云失败，准备重连", ex);
            scheduleReconnect();
        }
    }

    @Override
    public void stop() {
        desiredRunning.set(false);
        closeRecognizer();
        reconnectExecutor.shutdownNow();
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
                    long delayMillis = nextDelayMillis(attempt++);
                    log.error("连接腾讯云实时语音识别失败，{} ms 后重试", delayMillis, ex);
                    sleep(delayMillis);
                }
            }
        });
    }

    private void createAndStartRecognizer() throws Exception {
        Credential credential = new Credential(requireEnv("TENCENT_ASR_APP_ID"),
                requireEnv("TENCENT_ASR_SECRET_ID"),
                requireEnv("TENCENT_ASR_SECRET_KEY"));
        String voiceId = "ai-live-" + UUID.randomUUID();
        RealtimeRecognizerV2 next = new RealtimeRecognizerV2(
                properties.getAsr().getEngineModelType(),
                credential,
                voiceId,
                new TencentListener());
        next.setVoiceFormat(RealtimeRecognizerV2.AUDIO_FORMAT_PCM);
        next.setNeedVad(1);
        synchronized (lock) {
            closeRecognizer();
            recognizer = next;
            recognizer.start();
        }
        log.info("已连接腾讯云实时语音识别: voiceId={}, engine={}", voiceId, properties.getAsr().getEngineModelType());
    }

    private void scheduleReconnect() {
        if (!desiredRunning.get()) {
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

    private String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量: " + name);
        }
        return value;
    }

    private void publish(RealtimeRecognitionResponseV2 response, boolean finalResult) {
        String text = extractText(response);
        if (text == null || text.isBlank()) {
            return;
        }
        log.info(finalResult ? "[ASR][FINAL] {}" : "[ASR][TEMP] {}", text);
        SpeechRecognitionResult result = new SpeechRecognitionResult(text, finalResult, response.getVoiceId(), Instant.now());
        listeners.forEach(listener -> listener.onResult(result));
    }

    private String extractText(RealtimeRecognitionResponseV2 response) {
        SentenceResult sentences = response.getSentences();
        if (sentences == null || sentences.getSentenceList() == null || sentences.getSentenceList().isEmpty()) {
            return null;
        }
        return sentences.getSentenceList().stream()
                .map(SentenceItem::getSentence)
                .filter(Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private class TencentListener implements RealtimeRecognitionListenerV2 {
        @Override
        public void onRecognitionStart(RealtimeRecognitionResponseV2 response) {
            log.info("腾讯云实时语音识别开始: voiceId={}", response.getVoiceId());
        }

        @Override
        public void onRecognitionSentences(RealtimeRecognitionResponseV2 response) {
            publish(response, false);
        }

        @Override
        public void onSentenceEnd(RealtimeRecognitionResponseV2 response) {
            publish(response, true);
        }

        @Override
        public void onFail(RealtimeRecognitionResponseV2 response, Exception exception) {
            log.error("腾讯云实时语音识别错误: code={}, message={}",
                    response == null ? null : response.getCode(),
                    response == null ? null : response.getMessage(),
                    exception);
            scheduleReconnect();
        }
    }
}
