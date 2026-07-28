package cn.ai.live.edgeagent.audio;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 音频链路诊断计数器。
 * <p>只记录统计值，不保存或暴露原始语音内容。</p>
 */
@Component
public class AudioDiagnostics {
    private final AtomicLong microphoneBytesRead = new AtomicLong();
    private final AtomicLong microphonePacketsRead = new AtomicLong();
    private final AtomicLong convertedPcmBytes = new AtomicLong();
    private final AtomicLong convertedPcmPackets = new AtomicLong();
    private final AtomicLong asrBytesSent = new AtomicLong();
    private final AtomicLong asrPacketsSent = new AtomicLong();
    private final AtomicLong droppedAudioBytes = new AtomicLong();
    private volatile Instant lastAudioReadAt;
    private volatile Instant lastAudioConvertedAt;
    private volatile Instant lastAsrWriteAt;
    private volatile Instant lastNonSilentAudioAt;
    private volatile double rawAudioLevel;
    private volatile double rawAudioPeak;
    private volatile double convertedAudioLevel;
    private volatile double convertedAudioPeak;
    private volatile double rawNonZeroSampleRatio;
    private volatile double convertedNonZeroSampleRatio;
    private volatile double audioPeakLastSecond;
    private volatile long peakSecond = System.currentTimeMillis() / 1000;

    public void recordMicrophoneRead(int bytes, PcmLevelStats rawStats) {
        microphoneBytesRead.addAndGet(bytes);
        microphonePacketsRead.incrementAndGet();
        rawAudioLevel = rawStats.rmsLevel();
        rawAudioPeak = rawStats.peakLevel();
        rawNonZeroSampleRatio = rawStats.nonZeroSampleRatio();
        if (rawAudioPeak > 0.01d) {
            lastNonSilentAudioAt = Instant.now();
        }
        lastAudioReadAt = Instant.now();
    }

    public void recordConvertedPcm(byte[] pcm, PcmLevelStats convertedStats) {
        convertedPcmBytes.addAndGet(pcm.length);
        convertedPcmPackets.incrementAndGet();
        convertedAudioLevel = convertedStats.rmsLevel();
        convertedAudioPeak = convertedStats.peakLevel();
        convertedNonZeroSampleRatio = convertedStats.nonZeroSampleRatio();
        updatePeakLastSecond(convertedAudioPeak);
        if (convertedAudioPeak > 0.01d) {
            lastNonSilentAudioAt = Instant.now();
        }
        lastAudioConvertedAt = Instant.now();
    }

    public void recordAsrWrite(int bytes) {
        asrBytesSent.addAndGet(bytes);
        asrPacketsSent.incrementAndGet();
        lastAsrWriteAt = Instant.now();
    }

    public void recordDroppedAudio(int bytes) {
        droppedAudioBytes.addAndGet(bytes);
    }

    private synchronized void updatePeakLastSecond(double level) {
        long currentSecond = System.currentTimeMillis() / 1000;
        if (currentSecond != peakSecond) {
            peakSecond = currentSecond;
            audioPeakLastSecond = level;
            return;
        }
        audioPeakLastSecond = Math.max(audioPeakLastSecond, level);
    }

    public long microphoneBytesRead() {
        return microphoneBytesRead.get();
    }

    public long microphonePacketsRead() {
        return microphonePacketsRead.get();
    }

    public long convertedPcmBytes() {
        return convertedPcmBytes.get();
    }

    public long convertedPcmPackets() {
        return convertedPcmPackets.get();
    }

    public long asrBytesSent() {
        return asrBytesSent.get();
    }

    public long asrPacketsSent() {
        return asrPacketsSent.get();
    }

    public long droppedAudioBytes() {
        return droppedAudioBytes.get();
    }

    public Instant lastAudioReadAt() {
        return lastAudioReadAt;
    }

    public Instant lastAudioConvertedAt() {
        return lastAudioConvertedAt;
    }

    public Instant lastAsrWriteAt() {
        return lastAsrWriteAt;
    }

    public double audioLevel() {
        return convertedAudioLevel;
    }

    public double audioPeakLastSecond() {
        return audioPeakLastSecond;
    }

    public boolean silenceDetected() {
        return convertedSilenceDetected();
    }

    public double rawAudioLevel() {
        return rawAudioLevel;
    }

    public double rawAudioPeak() {
        return rawAudioPeak;
    }

    public double convertedAudioLevel() {
        return convertedAudioLevel;
    }

    public double convertedAudioPeak() {
        return convertedAudioPeak;
    }

    public boolean rawSilenceDetected() {
        return rawAudioPeak < 0.01d;
    }

    public boolean convertedSilenceDetected() {
        return convertedAudioPeak < 0.01d;
    }

    public double rawNonZeroSampleRatio() {
        return rawNonZeroSampleRatio;
    }

    public double convertedNonZeroSampleRatio() {
        return convertedNonZeroSampleRatio;
    }

    public Instant lastNonSilentAudioAt() {
        return lastNonSilentAudioAt;
    }
}
