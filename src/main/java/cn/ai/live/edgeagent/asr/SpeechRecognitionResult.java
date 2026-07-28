package cn.ai.live.edgeagent.asr;

import java.time.Instant;

public record SpeechRecognitionResult(String text, boolean finalResult, String voiceId, Instant receivedAt) {
}
