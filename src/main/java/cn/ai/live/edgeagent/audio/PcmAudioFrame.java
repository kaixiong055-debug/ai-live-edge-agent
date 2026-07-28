package cn.ai.live.edgeagent.audio;

import java.time.Instant;

public record PcmAudioFrame(byte[] data, int sampleRate, int bitsPerSample, int channels, Instant capturedAt) {
}
