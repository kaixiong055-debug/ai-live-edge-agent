package cn.ai.live.edgeagent.asr;

import org.springframework.stereotype.Component;

@Component
public class Pcm16ToFloatConverter {
    public float[] toFloatSamples(byte[] pcm) {
        if ((pcm.length & 1) == 1) {
            throw new IllegalArgumentException("PCM 长度必须为偶数字节");
        }
        float[] samples = new float[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) {
            int offset = i * 2;
            short sample = (short) (((pcm[offset + 1] & 0xff) << 8) | (pcm[offset] & 0xff));
            samples[i] = sample / 32768.0f;
        }
        return samples;
    }
}
