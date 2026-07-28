package cn.ai.live.edgeagent.audio;

import cn.ai.live.edgeagent.config.AiLiveProperties;
import cn.ai.live.edgeagent.runtime.RuntimeEventRecorder;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.TargetDataLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MicrophoneCaptureService {

    private final AudioDeviceService audioDeviceService;
    private final PcmFormatConverter converter;
    private final PcmLevelMeter levelMeter;
    private final AiLiveProperties properties;
    private final AudioDiagnostics diagnostics;
    private final RuntimeEventRecorder recorder;
    private final AtomicBoolean serviceRunning = new AtomicBoolean(false);
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private ScheduledExecutorService scanExecutor;
    private ExecutorService captureExecutor;
    private ScheduledFuture<?> scanTask;
    private Consumer<PcmAudioFrame> frameConsumer;
    private TargetDataLine line;
    private AudioFormat actualFormat;
    private volatile MicrophoneStatus status = MicrophoneStatus.STOPPED;
    private volatile String deviceName = "";
    private volatile String actualFormatText = "";
    private volatile Instant lastStartedAt;
    private volatile String lastError;
    private volatile boolean firstReadLogged;
    private volatile boolean firstConvertLogged;
    private volatile long lastDebugLogAt;
    private volatile boolean captureThreadAlive;
    private volatile String captureThreadExitReason;

    public MicrophoneCaptureService(AudioDeviceService audioDeviceService, PcmFormatConverter converter,
                                    AiLiveProperties properties, AudioDiagnostics diagnostics,
                                    RuntimeEventRecorder recorder) {
        this(audioDeviceService, converter, new PcmLevelMeter(), properties, diagnostics, recorder);
    }

    @Autowired
    public MicrophoneCaptureService(AudioDeviceService audioDeviceService, PcmFormatConverter converter,
                                    PcmLevelMeter levelMeter, AiLiveProperties properties, AudioDiagnostics diagnostics,
                                    RuntimeEventRecorder recorder) {
        this.audioDeviceService = audioDeviceService;
        this.converter = converter;
        this.levelMeter = levelMeter;
        this.properties = properties;
        this.diagnostics = diagnostics;
        this.recorder = recorder;
    }

    public synchronized void start(String ignoredLegacyName, int frameMillis, Consumer<PcmAudioFrame> consumer) {
        if (!serviceRunning.compareAndSet(false, true)) {
            return;
        }
        this.frameConsumer = consumer;
        scanExecutor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "microphone-auto-scan"));
        captureExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "microphone-capture"));
        status = MicrophoneStatus.DETECTING;
        scanTask = scanExecutor.scheduleWithFixedDelay(this::scanAndOpenIfNeeded, 0,
                properties.getAudio().getAutoScanIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("麦克风自动选择已启动，device-name={}", properties.getAudio().getDeviceName().isBlank() ? "<auto>" : properties.getAudio().getDeviceName());
    }

    private void scanAndOpenIfNeeded() {
        if (!serviceRunning.get() || capturing.get()) {
            return;
        }
        MicrophoneStatus previousStatus = status;
        if (previousStatus == MicrophoneStatus.STOPPED || previousStatus == MicrophoneStatus.NO_DEVICE || previousStatus == MicrophoneStatus.DETECTING) {
            status = MicrophoneStatus.DETECTING;
        } else if (previousStatus != MicrophoneStatus.DEVICE_BUSY && previousStatus != MicrophoneStatus.PERMISSION_DENIED) {
            status = MicrophoneStatus.RETRYING;
        }
        try {
            AudioCaptureLine captureLine = audioDeviceService.openCaptureLine(properties.getAudio().getDeviceName());
            synchronized (this) {
                line = captureLine.line();
                actualFormat = captureLine.sourceFormat();
                deviceName = captureLine.deviceName();
                actualFormatText = formatText(actualFormat);
                lastStartedAt = Instant.now();
                lastError = null;
                captureThreadExitReason = null;
                firstReadLogged = false;
                firstConvertLogged = false;
                status = MicrophoneStatus.STARTING;
                capturing.set(true);
                log.info("[麦克风] 已选择设备: {}", deviceName);
                log.info("[麦克风] 实际采集格式: {}", actualFormatText);
                captureExecutor.submit(this::captureLoop);
            }
        } catch (AudioOpenException ex) {
            // 忙碌、无权限、拔出后的重试状态要持续可见，避免下一轮扫描误显示成“无设备”。
            if (ex.status() == MicrophoneStatus.NO_DEVICE && shouldKeepPreviousDiagnosticStatus(previousStatus)) {
                status = previousStatus;
            } else {
                status = ex.status();
            }
            lastError = ex.getMessage();
            if (status == MicrophoneStatus.NO_DEVICE) {
                log.info("未检测到麦克风，请连接麦克风。");
            } else {
                log.warn("麦克风暂不可用: status={}, message={}", status, ex.getMessage());
            }
        } catch (Exception ex) {
            status = MicrophoneStatus.FAILED;
            lastError = ex.getMessage();
            log.warn("麦克风自动检测失败", ex);
        }
    }

    private void captureLoop() {
        captureThreadAlive = true;
        captureThreadExitReason = null;
        int sourceBytesPerSecond = Math.max(1, (int) (actualFormat.getSampleRate() * actualFormat.getFrameSize()));
        int bufferSize = Math.max(actualFormat.getFrameSize(), sourceBytesPerSecond * properties.getAsr().getAudioFrameMillis() / 1000);
        byte[] buffer = new byte[bufferSize];
        try {
            line.start();
            status = MicrophoneStatus.RUNNING;
            log.info("[麦克风] 采集线程已启动");
            while (serviceRunning.get() && capturing.get()) {
                int read = line.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                PcmLevelStats rawStats = levelMeter.measure(buffer, read, actualFormat);
                diagnostics.recordMicrophoneRead(read, rawStats);
                if (!firstReadLogged) {
                    firstReadLogged = true;
                    log.info("[麦克风] 已读取首个音频数据包: bytes={}", read);
                }
                byte[] converted = converter.toAsrPcm16kMono(buffer, read, actualFormat);
                if (converted.length == 0) {
                    continue;
                }
                PcmLevelStats convertedStats = levelMeter.measure(converted, converted.length, AudioDeviceService.TARGET_ASR_FORMAT);
                diagnostics.recordConvertedPcm(converted, convertedStats);
                if (!firstConvertLogged) {
                    firstConvertLogged = true;
                    log.info("[音频转换] 首个输出数据包: inputBytes={}, outputBytes={}, target=16000Hz/16bit/mono/LE",
                            read, converted.length);
                }
                logDebugStatsIfNeeded();
                frameConsumer.accept(new PcmAudioFrame(converted, 16000, 16, 1, Instant.now()));
            }
        } catch (Exception ex) {
            lastError = ex.getMessage();
            captureThreadExitReason = "exception: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            status = MicrophoneStatus.FAILED;
            recorder.recordError("MIC_CAPTURE_FAILED", captureThreadExitReason, "MicrophoneCaptureService");
            log.warn("麦克风读取失败，进入自动重试", ex);
        } finally {
            if (captureThreadExitReason == null) {
                captureThreadExitReason = serviceRunning.get() ? "capture-loop-retrying" : "service-stopped";
            }
            recorder.recordError("MIC_CAPTURE_LOOP_EXIT", captureThreadExitReason, "MicrophoneCaptureService");
            captureThreadAlive = false;
            log.info("[麦克风] 采集线程已退出: reason={}", captureThreadExitReason);
            closeCurrentLine();
            if (serviceRunning.get()) {
                status = MicrophoneStatus.RETRYING;
            }
        }
    }

    private void logDebugStatsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastDebugLogAt < 5000) {
            return;
        }
        lastDebugLogAt = now;
        log.debug("[音频链路] microphoneBytesRead={}, convertedPcmBytes={}, audioLevel={}, silenceDetected={}",
                diagnostics.microphoneBytesRead(), diagnostics.convertedPcmBytes(),
                "%.4f".formatted(diagnostics.convertedAudioLevel()), diagnostics.silenceDetected());
        log.debug("[音频链路] readBytes={}, convertedBytes={}, sentBytes={}, readPackets={}, sentPackets={}, audioLevel={}, captureThreadAlive={}",
                diagnostics.microphoneBytesRead(), diagnostics.convertedPcmBytes(), diagnostics.asrBytesSent(),
                diagnostics.microphonePacketsRead(), diagnostics.asrPacketsSent(),
                "%.4f".formatted(diagnostics.convertedAudioLevel()), captureThreadAlive);
    }

    private boolean shouldKeepPreviousDiagnosticStatus(MicrophoneStatus previousStatus) {
        return previousStatus == MicrophoneStatus.DEVICE_BUSY
                || previousStatus == MicrophoneStatus.PERMISSION_DENIED
                || previousStatus == MicrophoneStatus.RETRYING
                || previousStatus == MicrophoneStatus.FAILED;
    }

    public synchronized void stop() {
        serviceRunning.set(false);
        capturing.set(false);
        if (scanTask != null) {
            scanTask.cancel(false);
            scanTask = null;
        }
        closeCurrentLine();
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        if (captureExecutor != null) {
            captureExecutor.shutdownNow();
            captureExecutor = null;
        }
        status = MicrophoneStatus.STOPPED;
    }

    private synchronized void closeCurrentLine() {
        capturing.set(false);
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ex) {
                log.debug("关闭麦克风设备时发生异常: {}", ex.getMessage());
            } finally {
                line = null;
            }
        }
    }

    public boolean isRunning() {
        return capturing.get();
    }

    public String status() {
        return status.name();
    }

    public String statusMessage() {
        if (status == MicrophoneStatus.NO_DEVICE) {
            return "未检测到麦克风，请连接麦克风。";
        }
        return lastError;
    }

    public String deviceName() {
        return deviceName;
    }

    public String actualFormatText() {
        return actualFormatText;
    }

    public Instant lastStartedAt() {
        return lastStartedAt;
    }

    public long microphoneBytesRead() {
        return diagnostics.microphoneBytesRead();
    }

    public long microphonePacketsRead() {
        return diagnostics.microphonePacketsRead();
    }

    public long convertedPcmBytes() {
        return diagnostics.convertedPcmBytes();
    }

    public long convertedPcmPackets() {
        return diagnostics.convertedPcmPackets();
    }

    public Instant lastAudioReadAt() {
        return diagnostics.lastAudioReadAt();
    }

    public Instant lastAudioConvertedAt() {
        return diagnostics.lastAudioConvertedAt();
    }

    public double audioLevel() {
        return diagnostics.audioLevel();
    }

    public double rawAudioLevel() {
        return diagnostics.rawAudioLevel();
    }

    public double rawAudioPeak() {
        return diagnostics.rawAudioPeak();
    }

    public double convertedAudioLevel() {
        return diagnostics.convertedAudioLevel();
    }

    public double convertedAudioPeak() {
        return diagnostics.convertedAudioPeak();
    }

    public boolean rawSilenceDetected() {
        return diagnostics.rawSilenceDetected();
    }

    public boolean convertedSilenceDetected() {
        return diagnostics.convertedSilenceDetected();
    }

    public double rawNonZeroSampleRatio() {
        return diagnostics.rawNonZeroSampleRatio();
    }

    public double convertedNonZeroSampleRatio() {
        return diagnostics.convertedNonZeroSampleRatio();
    }

    public Instant lastNonSilentAudioAt() {
        return diagnostics.lastNonSilentAudioAt();
    }

    public boolean silenceDetected() {
        return diagnostics.silenceDetected();
    }

    public double audioPeakLastSecond() {
        return diagnostics.audioPeakLastSecond();
    }

    public boolean microphoneCaptureThreadAlive() {
        return captureThreadAlive;
    }

    public String captureThreadExitReason() {
        return captureThreadExitReason;
    }

    private String formatText(AudioFormat format) {
        return "%s %.0fHz %dbit %dch %s".formatted(format.getEncoding(), format.getSampleRate(),
                format.getSampleSizeInBits(), format.getChannels(), format.isBigEndian() ? "big-endian" : "little-endian");
    }
}
