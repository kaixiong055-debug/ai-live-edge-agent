package cn.ai.live.edgeagent.integrations.vtubestudio;

import cn.ai.live.edgeagent.action.ActionExecutionResult;
import cn.ai.live.edgeagent.action.ActionTargets;
import cn.ai.live.edgeagent.action.ActionType;
import cn.ai.live.edgeagent.action.TransitionType;
import cn.ai.live.edgeagent.action.ActionCommand;
import cn.ai.live.edgeagent.action.ActionDispatcher;
import cn.ai.live.edgeagent.runtime.ActionSource;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/local-api/integrations/vtube-studio")
@ConditionalOnProperty(prefix = "ai-live.local-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VTubeStudioController {
    private final VTubeStudioIntegrationService integration;
    private final ActionDispatcher dispatcher;

    public VTubeStudioController(VTubeStudioIntegrationService integration, ActionDispatcher dispatcher) {
        this.integration = integration;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/status")
    public VTubeStudioStatusSnapshot status() {
        return integration.status();
    }

    @PostMapping("/connect")
    public ResponseEntity<VTubeStudioStatusSnapshot> connect() {
        return ResponseEntity.ok(integration.connect().toCompletableFuture().join());
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect() {
        integration.disconnect();
        return Map.of("success", true);
    }

    @PostMapping("/authorize")
    public ResponseEntity<VTubeStudioStatusSnapshot> authorize() {
        return ResponseEntity.ok(integration.authorize().toCompletableFuture().join());
    }

    @PostMapping("/refresh")
    public ResponseEntity<VTubeStudioStatusSnapshot> refresh() {
        return ResponseEntity.ok(integration.refresh().toCompletableFuture().join());
    }

    @GetMapping("/hotkeys")
    public List<VTubeStudioHotkey> hotkeys() {
        return integration.hotkeys();
    }

    @PostMapping("/hotkeys/test")
    public ResponseEntity<Map<String, Object>> testHotkey(@RequestBody VTubeStudioHotkeyTestRequest request) {
        ActionCommand command = new ActionCommand(
                "vts_hotkey_test",
                "VTube Studio Hotkey 测试",
                ActionTargets.VTUBE_STUDIO,
                ActionType.TRIGGER_HOTKEY,
                null,
                0,
                false,
                TransitionType.NONE,
                0,
                request.toParameters());
        ActionExecutionResult result = dispatcher.dispatch(command, ActionSource.LOCAL_TEST);
        return ResponseEntity.ok(Map.of(
                "success", result.accepted(),
                "status", result.status(),
                "errorCode", result.errorCode() == null ? "" : result.errorCode(),
                "message", result.message() == null ? "" : result.message()));
    }
}
