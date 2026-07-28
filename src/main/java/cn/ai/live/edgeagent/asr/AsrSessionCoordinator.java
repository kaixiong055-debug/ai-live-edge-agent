package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.audio.MicrophoneCaptureService;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 统一管理 ASR 会话生命周期，包括麦克风采集、Provider 连接/断开、连接意图。
 * <p>
 * 连接流程：设置 connectionDesired → 校验配置 → 启动 ASR Provider → 启动麦克风。
 * 断开流程：设置 connectionDesired=false → 停止 ASR Provider → 停止麦克风 → 取消重连。
 * </p>
 */
@Slf4j
@Service
public class AsrSessionCoordinator {

    private final AiLiveProperties properties;
    private final MicrophoneCaptureService microphoneCaptureService;
    private final SpeechRecognitionGateway asrGateway;

    private final AtomicBoolean connectionDesired = new AtomicBoolean(false);
    private volatile Instant lastConnectedAt;
    private volatile Instant lastDisconnectedAt;

    public AsrSessionCoordinator(AiLiveProperties properties,
                                 MicrophoneCaptureService microphoneCaptureService,
                                 SpeechRecognitionGateway asrGateway) {
        this.properties = properties;
        this.microphoneCaptureService = microphoneCaptureService;
        this.asrGateway = asrGateway;
    }

    /**
     * Agent 启动时调用。根据 auto-connect 配置决定是否自动连接。
     *
     * @return true 表示已触发自动连接，false 表示等待手动连接
     */
    public boolean onBoot() {
        if (properties.getAsr().isAutoConnect()) {
            log.info("[ASR] 启动方式为自动，正在发起初始连接");
            connect();
            return true;
        }
        log.info("[ASR] 启动方式为手动，等待用户连接");
        return false;
    }

    /**
     * 用户请求连接 ASR。
     * 幂等：重复调用不会创建多个连接。
     */
    public synchronized void connect() {
        if (connectionDesired.get()) {
            String currentStatus = asrGateway.getStatus();
            log.info("[ASR] 连接请求已接受，但已在连接中或已连接: status={}", currentStatus);
            return;
        }

        connectionDesired.set(true);
        log.info("[ASR] 用户请求连接: provider={}", asrGateway.getProviderType());

        // 启动 ASR Provider（腾讯云或 Sherpa）
        try {
            asrGateway.start();
        } catch (Exception ex) {
            log.error("[ASR] 启动 ASR Provider 失败", ex);
            connectionDesired.set(false);
            return;
        }

        // 启动麦克风采集，音频回调到 ASR Provider
        try {
            microphoneCaptureService.start(
                    properties.getAudio().getDeviceName(),
                    properties.getAsr().getAudioFrameMillis(),
                    asrGateway::sendAudio);
        } catch (Exception ex) {
            log.error("[ASR] 启动麦克风采集失败，将停止 ASR Provider", ex);
            asrGateway.stop();
            connectionDesired.set(false);
            return;
        }

        lastConnectedAt = Instant.now();
    }

    /**
     * 用户请求断开 ASR。
     * 幂等：重复调用安全。
     */
    public synchronized void disconnect() {
        if (!connectionDesired.get()) {
            log.debug("[ASR] 断开请求，但当前未请求连接");
            // 即使未请求连接，仍然尝试清理残留状态
            cleanupResources();
            return;
        }

        log.info("[ASR] 用户请求断开");
        connectionDesired.set(false);
        lastDisconnectedAt = Instant.now();
        cleanupResources();
        log.info("[ASR] 已断开，麦克风采集已停止");
    }

    /**
     * Agent 关闭时调用。
     */
    public synchronized void shutdown() {
        connectionDesired.set(false);
        cleanupResources();
        log.info("[ASR] Agent 关闭，ASR 会话已清理");
    }

    private void cleanupResources() {
        // 先停止 ASR（取消重连、关闭 Recognizer、清理 PCM 缓冲区）
        asrGateway.stop();
        // 再停止麦克风（关闭音频设备、停止采集线程）
        microphoneCaptureService.stop();
        lastDisconnectedAt = Instant.now();
    }

    // ======================== 状态查询 ========================

    public boolean isConnectionDesired() {
        return connectionDesired.get();
    }

    public boolean isAutoConnect() {
        return properties.getAsr().isAutoConnect();
    }

    public String getAsrStatus() {
        return asrGateway.getStatus();
    }

    public String getMicrophoneStatus() {
        return microphoneCaptureService.status();
    }

    public boolean isAsrConnected() {
        return asrGateway.isReady();
    }

    public Instant getLastConnectedAt() {
        return lastConnectedAt;
    }

    public Instant getLastDisconnectedAt() {
        return lastDisconnectedAt;
    }
}
