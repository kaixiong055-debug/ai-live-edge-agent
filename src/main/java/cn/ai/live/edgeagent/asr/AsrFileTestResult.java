package cn.ai.live.edgeagent.asr;

public record AsrFileTestResult(
        String provider,
        String text,
        long durationMs,
        long inferenceMs,
        boolean success,
        String errorCode
) {
}
