package cn.ai.live.edgeagent.audio;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.sound.sampled.TargetDataLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MicrophoneCaptureService {

    private final AudioDeviceService audioDeviceService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private TargetDataLine line;

    public MicrophoneCaptureService(AudioDeviceService audioDeviceService) {
        this.audioDeviceService = audioDeviceService;
    }

    public synchronized void start(String microphoneName, int frameMillis, Consumer<PcmAudioFrame> consumer) throws Exception {
        if (!running.compareAndSet(false, true)) {
            log.warn("麦克风采集已经启动");
            return;
        }
        line = audioDeviceService.openTargetDataLine(microphoneName);
        int frameSize = Math.max(320, 16000 * 2 * frameMillis / 1000);
        executorService = Executors.newSingleThreadExecutor(r -> new Thread(r, "microphone-capture"));
        line.start();
        executorService.submit(() -> captureLoop(frameSize, consumer));
        log.info("麦克风采集已启动: 16000Hz/16bit/mono/little-endian, frameMillis={}", frameMillis);
    }

    private void captureLoop(int frameSize, Consumer<PcmAudioFrame> consumer) {
        byte[] buffer = new byte[frameSize];
        try {
            while (running.get()) {
                int read = line.read(buffer, 0, buffer.length);
                if (read > 0) {
                    byte[] data = new byte[read];
                    System.arraycopy(buffer, 0, data, 0, read);
                    consumer.accept(new PcmAudioFrame(data, 16000, 16, 1, Instant.now()));
                }
            }
        } catch (Exception ex) {
            if (running.get()) {
                log.error("麦克风采集异常", ex);
            }
        } finally {
            stop();
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
                log.info("麦克风采集已停止并释放设备");
            } catch (Exception ex) {
                log.warn("释放麦克风失败", ex);
            } finally {
                line = null;
            }
        }
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
