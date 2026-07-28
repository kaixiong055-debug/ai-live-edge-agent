package cn.ai.live.edgeagent.asr;

import java.time.Instant;

public record SpeechRecognitionResult(
        String text,
        boolean finalResult,
        String voiceId,
        Instant receivedAt,
        SpeechRecognitionProviderType provider,
        String sessionId,
        long sequence,
        long latencyMs,
        Double confidence
) {
    public SpeechRecognitionResult(String text, boolean finalResult, String voiceId, Instant receivedAt) {
        this(text, finalResult, voiceId, receivedAt, SpeechRecognitionProviderType.TENCENT, voiceId, 0, 0, null);
    }
}
