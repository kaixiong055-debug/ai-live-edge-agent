package cn.ai.live.edgeagent.audio;

import javax.sound.sampled.AudioFormat;
import org.springframework.stereotype.Component;

@Component
public class PcmLevelMeter {
    public PcmLevelStats measure(byte[] pcm, int length, AudioFormat format) {
        int channels = Math.max(1, format.getChannels());
        int frameSize = Math.max(channels * 2, format.getFrameSize());
        int frames = length / frameSize;
        if (frames <= 0) {
            return new PcmLevelStats(0d, 0d, 0d);
        }
        boolean bigEndian = format.isBigEndian();
        long sampleCount = 0;
        long nonZero = 0;
        double sumSquares = 0d;
        int peak = 0;
        for (int frame = 0; frame < frames; frame++) {
            int frameOffset = frame * frameSize;
            for (int ch = 0; ch < channels; ch++) {
                int offset = frameOffset + ch * 2;
                int sample = decodeSigned16(pcm[offset], pcm[offset + 1], bigEndian);
                int abs = Math.abs(sample);
                peak = Math.max(peak, abs);
                sumSquares += (double) sample * sample;
                if (sample != 0) {
                    nonZero++;
                }
                sampleCount++;
            }
        }
        double rms = Math.sqrt(sumSquares / sampleCount) / 32768.0d;
        double peakLevel = peak / 32768.0d;
        return new PcmLevelStats(rms, peakLevel, nonZero / (double) sampleCount);
    }

    public int decodeSigned16(byte lowOrHigh, byte highOrLow, boolean bigEndian) {
        int first = lowOrHigh & 0xff;
        int second = highOrLow & 0xff;
        return bigEndian ? (short) ((first << 8) | second) : (short) ((second << 8) | first);
    }
}
