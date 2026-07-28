package cn.ai.live.edgeagent.integrations.vtubestudio;

import cn.ai.live.edgeagent.action.ActionExecutionContext;
import cn.ai.live.edgeagent.action.ActionExecutionRequest;
import cn.ai.live.edgeagent.action.ActionExecutionResult;
import cn.ai.live.edgeagent.action.ActionExecutionStatus;
import cn.ai.live.edgeagent.action.ActionExecutor;
import cn.ai.live.edgeagent.action.ActionExecutorStatus;
import cn.ai.live.edgeagent.action.ActionTargets;
import cn.ai.live.edgeagent.action.ActionType;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VTubeStudioActionExecutor implements ActionExecutor {
    private final AiLiveProperties properties;
    private final VTubeStudioIntegrationService integration;

    public VTubeStudioActionExecutor(AiLiveProperties properties, VTubeStudioIntegrationService integration) {
        this.properties = properties;
        this.integration = integration;
    }

    @Override
    public String targetId() {
        return ActionTargets.VTUBE_STUDIO;
    }

    @Override
    public boolean supports(ActionExecutionRequest request) {
        return request.actionType() == ActionType.TRIGGER_HOTKEY;
    }

    @Override
    public CompletionStage<ActionExecutionResult> execute(ActionExecutionRequest request, ActionExecutionContext context) {
        Instant startedAt = Instant.now();
        if (!properties.getIntegrations().getVtubeStudio().isEnabled()) {
            return CompletableFuture.completedFuture(ActionExecutionResult.failure(request,
                    ActionExecutionStatus.TARGET_DISABLED, VTubeStudioErrorCodes.VTS_DISABLED,
                    "VTube Studio 适配器未启用", startedAt));
        }
        VTubeStudioStatusSnapshot status = integration.status();
        if (!status.websocketConnected()) {
            return CompletableFuture.completedFuture(ActionExecutionResult.failure(request,
                    ActionExecutionStatus.TARGET_UNAVAILABLE, VTubeStudioErrorCodes.VTS_CONNECTION_CLOSED,
                    "VTube Studio WebSocket 未连接", startedAt));
        }
        if (!status.authenticated()) {
            return CompletableFuture.completedFuture(ActionExecutionResult.failure(request,
                    ActionExecutionStatus.UNAUTHENTICATED, VTubeStudioErrorCodes.VTS_NOT_AUTHENTICATED,
                    "VTube Studio 尚未授权", startedAt));
        }
        if (!status.modelLoaded()) {
            return CompletableFuture.completedFuture(ActionExecutionResult.failure(request,
                    ActionExecutionStatus.TARGET_UNAVAILABLE, VTubeStudioErrorCodes.VTS_NO_MODEL,
                    "VTube Studio 当前没有加载模型", startedAt));
        }

        HotkeyParameters params = parseParameters(request.parameters());
        if (!params.valid()) {
            return CompletableFuture.completedFuture(ActionExecutionResult.failure(request,
                    ActionExecutionStatus.INVALID_ACTION, "INVALID_ACTION", "hotkeyId 和 hotkeyName 不能同时为空", startedAt));
        }
        VTubeStudioHotkey hotkey = integration.findHotkey(params.hotkeyId(), params.hotkeyName());
        if (hotkey == null) {
            return CompletableFuture.completedFuture(ActionExecutionResult.failure(request,
                    ActionExecutionStatus.ACTION_NOT_FOUND, VTubeStudioErrorCodes.VTS_HOTKEY_NOT_FOUND,
                    "未找到 VTube Studio Hotkey", startedAt));
        }
        if (hotkey.duplicateName()) {
            log.warn("VTube Studio Hotkey 名称重复，将使用官方返回的第一个: target={}, actionCode={}, requestId={}, hotkeyName={}",
                    request.target(), request.actionCode(), request.requestId(), hotkey.name());
        }
        return integration.triggerHotkey(hotkey.hotkeyId())
                .thenApply(ignored -> ActionExecutionResult.success(request, "VTube Studio Hotkey 已触发", startedAt))
                .exceptionally(ex -> toFailure(request, startedAt, ex));
    }

    @Override
    public ActionExecutorStatus status() {
        if (!properties.getIntegrations().getVtubeStudio().isEnabled()) {
            return ActionExecutorStatus.DISABLED;
        }
        VTubeStudioConnectionStatus connectionStatus = integration.status().connectionStatus();
        return connectionStatus == VTubeStudioConnectionStatus.READY ? ActionExecutorStatus.READY : ActionExecutorStatus.UNAVAILABLE;
    }

    private HotkeyParameters parseParameters(JsonNode parameters) {
        if (parameters == null || !parameters.isObject()) {
            return new HotkeyParameters(null, null);
        }
        String hotkeyId = text(parameters.get("hotkeyId"));
        String hotkeyName = text(parameters.get("hotkeyName"));
        if (tooLong(hotkeyId) || tooLong(hotkeyName)) {
            return new HotkeyParameters(null, null);
        }
        return new HotkeyParameters(hotkeyId, hotkeyName);
    }

    private ActionExecutionResult toFailure(ActionExecutionRequest request, Instant startedAt, Throwable ex) {
        Throwable root = ex.getCause() == null ? ex : ex.getCause();
        String errorCode = root instanceof VTubeStudioApiException api ? api.errorCode() : VTubeStudioErrorCodes.VTS_API_ERROR;
        ActionExecutionStatus status = VTubeStudioErrorCodes.VTS_REQUEST_TIMEOUT.equals(errorCode)
                ? ActionExecutionStatus.TIMEOUT : ActionExecutionStatus.FAILED;
        return ActionExecutionResult.failure(request, status, errorCode, root.getMessage(), startedAt);
    }

    private boolean tooLong(String value) {
        return value != null && value.length() > 128;
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private record HotkeyParameters(String hotkeyId, String hotkeyName) {
        boolean valid() {
            return (hotkeyId != null && !hotkeyId.isBlank()) || (hotkeyName != null && !hotkeyName.isBlank());
        }
    }
}
