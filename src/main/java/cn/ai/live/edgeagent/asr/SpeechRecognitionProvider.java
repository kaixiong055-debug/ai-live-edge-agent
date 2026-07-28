package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.audio.PcmAudioFrame;

public interface SpeechRecognitionProvider {

    void addListener(SpeechRecognitionListener listener);

    void start();

    default void acceptPcm(byte[] pcm) {
        sendAudio(new PcmAudioFrame(pcm, 16000, 16, 1, java.time.Instant.now()));
    }

    void sendAudio(PcmAudioFrame frame);

    void stop();

    default String getStatus() {
        return "UNKNOWN";
    }

    default SpeechRecognitionProviderType getProviderType() {
        return SpeechRecognitionProviderType.TENCENT;
    }

    default boolean isReady() {
        return false;
    }

    default void close() {
        stop();
    }
}
