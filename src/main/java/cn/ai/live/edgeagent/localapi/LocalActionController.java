package cn.ai.live.edgeagent.localapi;

import cn.ai.live.edgeagent.action.ActionCommand;
import cn.ai.live.edgeagent.action.ActionDispatcher;
import cn.ai.live.edgeagent.action.ActionExecutionResult;
import cn.ai.live.edgeagent.assets.AssetService;
import cn.ai.live.edgeagent.command.CommandConfigManager;
import cn.ai.live.edgeagent.command.CommandDefinition;
import cn.ai.live.edgeagent.runtime.ActionSource;
import cn.ai.live.edgeagent.runtime.RuntimeStatusService;
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
@RequestMapping("/local-api")
@ConditionalOnProperty(prefix = "ai-live.local-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LocalActionController {
    private final CommandConfigManager commandConfigManager;
    private final ActionDispatcher actionDispatcher;
    private final RuntimeStatusService runtimeStatusService;

    public LocalActionController(CommandConfigManager commandConfigManager,
                                 ActionDispatcher actionDispatcher,
                                 RuntimeStatusService runtimeStatusService) {
        this.commandConfigManager = commandConfigManager;
        this.actionDispatcher = actionDispatcher;
        this.runtimeStatusService = runtimeStatusService;
    }

    @PostMapping("/actions/test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody TestActionRequest request) {
        CommandDefinition command = commandConfigManager.currentConfig().getCommands().stream()
                .filter(item -> item.getCode().equals(request.actionCode()))
                .findFirst()
                .orElse(null);
        if (command == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "unknown actionCode"));
        }
        ActionExecutionResult result = actionDispatcher.dispatch(ActionCommand.from(command), ActionSource.LOCAL_TEST);
        return ResponseEntity.ok(Map.of("success", result.accepted(), "reason", result.reason()));
    }

    @PostMapping("/actions/clear")
    public Map<String, Object> clear() {
        ActionExecutionResult result = actionDispatcher.dispatch(ActionCommand.clear());
        return Map.of("success", result.accepted(), "reason", result.reason());
    }

    @GetMapping("/actions")
    public List<CommandDefinition> actions() {
        return commandConfigManager.currentConfig().getCommands();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "runtime", runtimeStatusService.snapshot());
    }
}
