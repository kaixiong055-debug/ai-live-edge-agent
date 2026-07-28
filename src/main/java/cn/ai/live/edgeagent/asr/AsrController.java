package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.config.AiLiveProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ASR 手动控制本地 API。
 * <p>
 * 仅通过 localhost 访问（server.address=127.0.0.1），用于 Console 页面的连接/断开控制。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/local-api/asr")
@ConditionalOnProperty(prefix = "ai-live.local-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AsrController {

    private final AsrSessionCoordinator coordinator;
    private final AiLiveProperties properties;
    private final SpeechRecognitionGateway asrGateway;

    public AsrController(AsrSessionCoordinator coordinator,
                         AiLiveProperties properties,
                         SpeechRecognitionGateway asrGateway) {
        this.coordinator = coordinator;
        this.properties = properties;
        this.asrGateway = asrGateway;
    }

    /**
     * POST /local-api/asr/connect
     * 异步启动 ASR 连接，不长时间阻塞 HTTP 请求。
     */
    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("connectionDesired", true);

        if (coordinator.isConnectionDesired()) {
            body.put("asrStatus", coordinator.getAsrStatus());
            body.put("message", "ASR 已在连接中或已连接");
            return ResponseEntity.ok(body);
        }

        // 异步执行连接，避免阻塞 HTTP 请求
        Thread connectThread = new Thread(() -> {
            try {
                coordinator.connect();
            } catch (Exception ex) {
                log.error("[ASR] 异步连接失败", ex);
            }
        }, "asr-connect-request");
        connectThread.setDaemon(true);
        connectThread.start();

        body.put("asrStatus", "CONNECTING");
        body.put("message", "ASR 连接请求已接受");
        return ResponseEntity.ok(body);
    }

    /**
     * POST /local-api/asr/disconnect
     * 断开 ASR 连接并停止麦克风。
     */
    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect() {
        coordinator.disconnect();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("connectionDesired", false);
        body.put("asrStatus", coordinator.getAsrStatus());
        body.put("message", "ASR 已断开");
        return ResponseEntity.ok(body);
    }

    /**
     * GET /local-api/asr/status
     * 返回 ASR 状态，不包含敏感信息。
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", asrGateway.getProviderType() != null ? asrGateway.getProviderType().name() : "UNKNOWN");
        body.put("autoConnect", coordinator.isAutoConnect());
        body.put("connectionDesired", coordinator.isConnectionDesired());
        body.put("asrStatus", coordinator.getAsrStatus());
        body.put("microphoneStatus", coordinator.getMicrophoneStatus());
        body.put("connected", coordinator.isAsrConnected());
        body.put("lastConnectedAt", coordinator.getLastConnectedAt() != null ? coordinator.getLastConnectedAt().toString() : null);
        body.put("lastDisconnectedAt", coordinator.getLastDisconnectedAt() != null ? coordinator.getLastDisconnectedAt().toString() : null);
        return ResponseEntity.ok(body);
    }
}
