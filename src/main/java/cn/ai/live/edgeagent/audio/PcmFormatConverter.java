package cn.ai.live.edgeagent.audio;

import java.util.Arrays;
import javax.sound.sampled.AudioFormat;
import org.springframework.stereotype.Component;

@Component
public class PcmFormatConverter {
    private byte[] pending = new byte[0];
    private double nextSourceFrameOffset;
    private String lastFormatKey = "";

    public synchronized byte[] toAsrPcm16kMono(byte[] source, int length, AudioFormat sourceFormat) {
        String formatKey = formatKey(sourceFormat);
        if (!formatKey.equals(lastFormatKey)) {
            pending = new byte[0];
            nextSourceFrameOffset = 0d;
            lastFormatKey = formatKey;
        }
        int sourceChannels = sourceFormat.getChannels();
        int sourceFrameSize = sourceFormat.getFrameSize();
        float sourceRate = sourceFormat.getSampleRate();
        boolean bigEndian = sourceFormat.isBigEndian();
        byte[] data = mergePending(source, length);
        int completeLength = data.length - (data.length % sourceFrameSize);
        pending = completeLength == data.length ? new byte[0] : Arrays.copyOfRange(data, completeLength, data.length);
        if (completeLength == 0) {
            return new byte[0];
        }
        if (isTargetFormat(sourceFormat)) {
            return Arrays.copyOf(data, completeLength);
        }
        int sourceFrames = completeLength / sourceFrameSize;
        double interval = sourceRate / 16000.0d;
        int targetFrames = 0;
        for (double pos = nextSourceFrameOffset; pos <= sourceFrames - 1; pos += interval) {
            targetFrames++;
        }
        byte[] target = new byte[targetFrames * 2];
        double sourceOffset = nextSourceFrameOffset;
        for (int i = 0; i < targetFrames; i++) {
            int sourceFrame = Math.min(sourceFrames - 1, (int) Math.floor(sourceOffset));
            int frameOffset = sourceFrame * sourceFrameSize;
            int mixed = 0;
            for (int ch = 0; ch < sourceChannels; ch++) {
                int sampleOffset = frameOffset + ch * 2;
                int lo = data[sampleOffset] & 0xff;
                int hi = data[sampleOffset + 1] & 0xff;
                int sample = bigEndian ? (short) ((lo << 8) | hi) : (short) ((hi << 8) | lo);
                mixed += sample;
            }
            short mono = (short) (mixed / sourceChannels);
            target[i * 2] = (byte) (mono & 0xff);
            target[i * 2 + 1] = (byte) ((mono >>> 8) & 0xff);
            sourceOffset += interval;
        }
        nextSourceFrameOffset = sourceOffset - sourceFrames;
        return target;
    }

    private byte[] mergePending(byte[] source, int length) {
        if (pending.length == 0) {
            return Arrays.copyOf(source, length);
        }
        byte[] merged = Arrays.copyOf(pending, pending.length + length);
        System.arraycopy(source, 0, merged, pending.length, length);
        return merged;
    }

    private boolean isTargetFormat(AudioFormat format) {
        return format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                && Math.round(format.getSampleRate()) == 16000
                && format.getSampleSizeInBits() == 16
                && format.getChannels() == 1
                && !format.isBigEndian();
    }

    private String formatKey(AudioFormat format) {
        return "%s/%s/%s/%s/%s".formatted(format.getEncoding(), format.getSampleRate(),
                format.getSampleSizeInBits(), format.getChannels(), format.isBigEndian());
    }
}
