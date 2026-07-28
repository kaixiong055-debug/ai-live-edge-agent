package cn.ai.live.edgeagent.audio;

import java.time.Instant;

public record AudioTestSnapshot(
        AudioTestStatus status,
        int remainingSeconds,
        double maxRawAudioPeak,
        double maxConvertedAudioPeak,
        double averageRawAudioLevel,
        double averageConvertedAudioLevel,
        String message,
        Instant startedAt,
        Instant finishedAt
) {
}
