package cn.ai.live.edgeagent.audio;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class AudioTestService {
    private static final int TEST_SECONDS = 5;
    private final MicrophoneCaptureService microphone;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "audio-test-monitor"));
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledFuture<?> task;
    private volatile AudioTestSnapshot snapshot = idle();
    private volatile double rawLevelSum;
    private volatile double convertedLevelSum;
    private volatile int samples;
    private volatile double maxRawPeak;
    private volatile double maxConvertedPeak;
    private volatile Instant startedAt;

    public AudioTestService(MicrophoneCaptureService microphone) {
        this.microphone = microphone;
    }

    public synchronized AudioTestSnapshot start() {
        if (!running.compareAndSet(false, true)) {
            return snapshot;
        }
        rawLevelSum = 0d;
        convertedLevelSum = 0d;
        samples = 0;
        maxRawPeak = 0d;
        maxConvertedPeak = 0d;
        startedAt = Instant.now();
        snapshot = new AudioTestSnapshot(AudioTestStatus.RUNNING, TEST_SECONDS, 0d, 0d, 0d, 0d,
                "请对着麦克风说话", startedAt, null);
        task = executor.scheduleAtFixedRate(this::sample, 0, 200, TimeUnit.MILLISECONDS);
        executor.schedule(this::finish, TEST_SECONDS, TimeUnit.SECONDS);
        return snapshot;
    }

    private void sample() {
        if (!running.get()) {
            return;
        }
        maxRawPeak = Math.max(maxRawPeak, microphone.rawAudioPeak());
        maxConvertedPeak = Math.max(maxConvertedPeak, microphone.convertedAudioPeak());
        rawLevelSum += microphone.rawAudioLevel();
        convertedLevelSum += microphone.convertedAudioLevel();
        samples++;
        int remaining = Math.max(0, TEST_SECONDS - (int) Duration.between(startedAt, Instant.now()).toSeconds());
        snapshot = new AudioTestSnapshot(AudioTestStatus.RUNNING, remaining, maxRawPeak, maxConvertedPeak,
                average(rawLevelSum), average(convertedLevelSum), "请对着麦克风说话", startedAt, null);
    }

    private synchronized void finish() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        AudioTestStatus status = evaluate();
        snapshot = new AudioTestSnapshot(status, 0, maxRawPeak, maxConvertedPeak,
                average(rawLevelSum), average(convertedLevelSum), message(status), startedAt, Instant.now());
    }

    private AudioTestStatus evaluate() {
        if (!microphone.isRunning()) {
            return AudioTestStatus.DEVICE_UNAVAILABLE;
        }
        if (maxRawPeak > 0.02d && maxConvertedPeak < 0.005d) {
            return AudioTestStatus.CONVERSION_SIGNAL_LOSS;
        }
        if (maxRawPeak < 0.01d && maxConvertedPeak < 0.01d) {
            return AudioTestStatus.NO_SIGNAL;
        }
        return AudioTestStatus.PASS;
    }

    private String message(AudioTestStatus status) {
        return switch (status) {
            case PASS -> "已检测到麦克风声音。";
            case NO_SIGNAL -> "麦克风已打开，但没有检测到有效声音。请检查 Windows 输入设备、麦克风静音和输入音量。";
            case CONVERSION_SIGNAL_LOSS -> "检测到原始麦克风声音，但音频格式转换后信号异常。";
            case DEVICE_UNAVAILABLE -> "未检测到可用麦克风。";
            case RUNNING -> "请对着麦克风说话";
            case IDLE -> "未开始测试";
        };
    }

    private double average(double sum) {
        return samples == 0 ? 0d : sum / samples;
    }

    public AudioTestSnapshot snapshot() {
        return snapshot;
    }

    @PreDestroy
    public void shutdown() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        running.set(false);
        executor.shutdownNow();
    }

    private static AudioTestSnapshot idle() {
        return new AudioTestSnapshot(AudioTestStatus.IDLE, 0, 0d, 0d, 0d, 0d, "未开始测试", null, null);
    }
}
