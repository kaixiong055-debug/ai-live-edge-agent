package cn.ai.live.edgeagent.audio;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * 将 16k/16bit/mono/LE PCM 聚合为更适合实时 ASR 的 200ms 数据包。
 */
public class PcmPacketAggregator {
    public static final int TARGET_PACKET_BYTES = 6400;
    private final byte[] buffer = new byte[TARGET_PACKET_BYTES];
    private int position;

    public synchronized void append(byte[] pcm, Consumer<byte[]> packetConsumer) {
        int offset = 0;
        while (offset < pcm.length) {
            int copy = Math.min(TARGET_PACKET_BYTES - position, pcm.length - offset);
            System.arraycopy(pcm, offset, buffer, position, copy);
            position += copy;
            offset += copy;
            if (position == TARGET_PACKET_BYTES) {
                packetConsumer.accept(Arrays.copyOf(buffer, TARGET_PACKET_BYTES));
                position = 0;
            }
        }
    }

    public synchronized void reset() {
        position = 0;
    }

    public synchronized int bufferedBytes() {
        return position;
    }
}
