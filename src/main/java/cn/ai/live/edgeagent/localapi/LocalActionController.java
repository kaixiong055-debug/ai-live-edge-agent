package cn.ai.live.edgeagent.localapi;

import cn.ai.live.edgeagent.action.ActionCommand;
import cn.ai.live.edgeagent.action.ActionDispatcher;
import cn.ai.live.edgeagent.action.ActionExecutionResult;
import cn.ai.live.edgeagent.assets.AssetService;
import cn.ai.live.edgeagent.audio.MicrophoneCaptureService;
import cn.ai.live.edgeagent.command.CommandConfig;
import cn.ai.live.edgeagent.command.CommandConfigLoader;
import cn.ai.live.edgeagent.command.CommandDefinition;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import cn.ai.live.edgeagent.renderer.RendererWebSocketGateway;
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
    private final CommandConfigLoader commandConfigLoader;
    private final ActionDispatcher actionDispatcher;
    private final AiLiveProperties properties;
    private final MicrophoneCaptureService microphoneCaptureService;
    private final RendererWebSocketGateway rendererGateway;
    private final AssetService assetService;

    public LocalActionController(CommandConfigLoader commandConfigLoader,
                                 ActionDispatcher actionDispatcher,
                                 AiLiveProperties properties,
                                 MicrophoneCaptureService microphoneCaptureService,
                                 RendererWebSocketGateway rendererGateway,
                                 AssetService assetService) {
        this.commandConfigLoader = commandConfigLoader;
        this.actionDispatcher = actionDispatcher;
        this.properties = properties;
        this.microphoneCaptureService = microphoneCaptureService;
        this.rendererGateway = rendererGateway;
        this.assetService = assetService;
    }

    @PostMapping("/actions/test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody TestActionRequest request) {
        CommandDefinition command = commandConfigLoader.currentConfig().getCommands().stream()
                .filter(item -> item.getCode().equals(request.actionCode()))
                .findFirst()
                .orElse(null);
        if (command == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "unknown actionCode"));
        }
        ActionExecutionResult result = actionDispatcher.dispatch(ActionCommand.from(command));
        return ResponseEntity.ok(Map.of("success", result.accepted(), "reason", result.reason()));
    }

    @PostMapping("/actions/clear")
    public Map<String, Object> clear() {
        ActionExecutionResult result = actionDispatcher.dispatch(ActionCommand.clear());
        return Map.of("success", result.accepted(), "reason", result.reason());
    }

    @GetMapping("/actions")
    public List<CommandDefinition> actions() {
        return commandConfigLoader.currentConfig().getCommands();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        CommandConfig config = commandConfigLoader.currentConfig();
        return Map.of(
                "status", "UP",
                "asrAutoStart", properties.getAsr().isAutoStart(),
                "microphoneRunning", microphoneCaptureService.isRunning(),
                "rendererConnections", rendererGateway.connectionCount(),
                "commandCount", config.getCommands().size(),
                "assetRoot", assetService.rootPath().toString(),
                "assetRootExists", assetService.rootExists()
        );
    }
}
