package cn.ai.live.edgeagent.renderer;

import cn.ai.live.edgeagent.action.ActionCommand;
import cn.ai.live.edgeagent.action.ActionExecutionCoordinator;
import cn.ai.live.edgeagent.action.ActionExecutionResult;
import cn.ai.live.edgeagent.action.ActionExecutor;
import cn.ai.live.edgeagent.action.ActionType;
import cn.ai.live.edgeagent.assets.AssetResolveResult;
import cn.ai.live.edgeagent.assets.AssetService;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RendererActionExecutor implements ActionExecutor {
    private final AssetService assetService;
    private final RendererWebSocketGateway gateway;
    private final ActionExecutionCoordinator coordinator;

    public RendererActionExecutor(AssetService assetService, RendererWebSocketGateway gateway, ActionExecutionCoordinator coordinator) {
        this.assetService = assetService;
        this.gateway = gateway;
        this.coordinator = coordinator;
    }

    @Override
    public ActionExecutionResult execute(ActionCommand command) {
        if (command.actionType() == ActionType.CLEAR) {
            gateway.broadcast(message("CLEAR_RENDERER", Map.of()));
            coordinator.accept(command, () -> {});
            return ActionExecutionResult.ok();
        }
        if (command.actionType() == ActionType.HIDE) {
            gateway.broadcast(message("HIDE_CURRENT", Map.of()));
            coordinator.accept(command, () -> {});
            return ActionExecutionResult.ok();
        }

        AssetResolveResult asset = assetService.resolve(command.assetPath(), command.actionType());
        if (!asset.success()) {
            log.warn("动作素材校验失败: code={}, reason={}", command.actionCode(), asset.error());
            return ActionExecutionResult.rejected(asset.error());
        }

        ActionExecutionResult accepted = coordinator.accept(command, () -> gateway.broadcast(message("HIDE_CURRENT", Map.of())));
        if (!accepted.accepted()) {
            return accepted;
        }
        RendererActionData data = new RendererActionData(
                command.actionCode(),
                command.actionType(),
                asset.assetUrl(),
                command.durationMs(),
                command.loop(),
                command.transition());
        gateway.broadcast(message("RENDER_ACTION", data));
        return ActionExecutionResult.ok();
    }

    RendererMessage message(String type, Object data) {
        return new RendererMessage(type, UUID.randomUUID().toString(), System.currentTimeMillis(), data);
    }
}
