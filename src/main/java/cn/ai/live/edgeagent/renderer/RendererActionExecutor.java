package cn.ai.live.edgeagent.renderer;

import cn.ai.live.edgeagent.action.ActionCommand;
import cn.ai.live.edgeagent.action.ActionExecutionContext;
import cn.ai.live.edgeagent.action.ActionExecutionCoordinator;
import cn.ai.live.edgeagent.action.ActionExecutionRequest;
import cn.ai.live.edgeagent.action.ActionExecutionResult;
import cn.ai.live.edgeagent.action.ActionExecutionStatus;
import cn.ai.live.edgeagent.action.ActionExecutor;
import cn.ai.live.edgeagent.action.ActionExecutorStatus;
import cn.ai.live.edgeagent.action.ActionTargets;
import cn.ai.live.edgeagent.action.ActionType;
import cn.ai.live.edgeagent.assets.AssetResolveResult;
import cn.ai.live.edgeagent.assets.AssetService;
import cn.ai.live.edgeagent.runtime.ActionSource;
import cn.ai.live.edgeagent.runtime.RuntimeEventRecorder;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RendererActionExecutor implements ActionExecutor {
    private static final Set<ActionType> SUPPORTED_TYPES = Set.of(
            ActionType.SHOW_IMAGE, ActionType.PLAY_GIF, ActionType.PLAY_WEBM, ActionType.HIDE, ActionType.CLEAR);

    private final AssetService assetService;
    private final RendererWebSocketGateway gateway;
    private final ActionExecutionCoordinator coordinator;
    private final RuntimeEventRecorder recorder;

    public RendererActionExecutor(AssetService assetService, RendererWebSocketGateway gateway, ActionExecutionCoordinator coordinator,
                                  RuntimeEventRecorder recorder) {
        this.assetService = assetService;
        this.gateway = gateway;
        this.coordinator = coordinator;
        this.recorder = recorder;
    }

    @Override
    public String targetId() {
        return ActionTargets.MEDIA;
    }

    @Override
    public boolean supports(ActionExecutionRequest request) {
        return SUPPORTED_TYPES.contains(request.actionType());
    }

    @Override
    public CompletionStage<ActionExecutionResult> execute(ActionExecutionRequest request, ActionExecutionContext context) {
        return CompletableFuture.completedFuture(executeMedia(request));
    }

    @Override
    public ActionExecutorStatus status() {
        return ActionExecutorStatus.READY;
    }

    private ActionExecutionResult executeMedia(ActionExecutionRequest request) {
        ActionCommand command = request.toCommand();
        if (command.actionType() == ActionType.CLEAR) {
            coordinator.accept(command, () -> {});
            if (gateway.connectionCount() == 0) {
                return noRenderer(request);
            }
            gateway.broadcast(message("CLEAR_RENDERER", Map.of()));
            return ActionExecutionResult.success(request, "CLEAR 已广播", request.createdAt());
        }
        if (command.actionType() == ActionType.HIDE) {
            coordinator.accept(command, () -> {});
            if (gateway.connectionCount() == 0) {
                return noRenderer(request);
            }
            gateway.broadcast(message("HIDE_CURRENT", Map.of()));
            return ActionExecutionResult.success(request, "HIDE 已广播", request.createdAt());
        }

        AssetResolveResult asset = assetService.resolve(command.assetPath(), command.actionType());
        if (!asset.success()) {
            log.warn("动作素材校验失败: target={}, code={}, reason={}", ActionTargets.MEDIA, command.actionCode(), asset.error());
            return ActionExecutionResult.failure(request, ActionExecutionStatus.INVALID_ACTION, asset.error(), asset.error(), request.createdAt());
        }

        ActionExecutionResult accepted = coordinator.accept(command, () -> {
            gateway.broadcast(message("HIDE_CURRENT", Map.of()));
            recorder.recordAutoHidden(command, ActionSource.SYSTEM);
        });
        if (!accepted.accepted()) {
            return ActionExecutionResult.failure(request, ActionExecutionStatus.FAILED, accepted.reason(), accepted.reason(), request.createdAt());
        }
        if (gateway.connectionCount() == 0) {
            return noRenderer(request);
        }
        RendererActionData data = new RendererActionData(
                command.actionCode(),
                command.actionType(),
                asset.assetUrl(),
                command.durationMs(),
                command.loop(),
                command.transition());
        gateway.broadcast(message("RENDER_ACTION", data));
        return ActionExecutionResult.success(request, "Renderer 动作已广播", request.createdAt());
    }

    private ActionExecutionResult noRenderer(ActionExecutionRequest request) {
        return ActionExecutionResult.failure(request, ActionExecutionStatus.TARGET_UNAVAILABLE,
                "RENDERER_NOT_CONNECTED", "当前没有 Renderer 连接，跳过广播。", request.createdAt());
    }

    RendererMessage message(String type, Object data) {
        return new RendererMessage(type, UUID.randomUUID().toString(), System.currentTimeMillis(), data);
    }
}
