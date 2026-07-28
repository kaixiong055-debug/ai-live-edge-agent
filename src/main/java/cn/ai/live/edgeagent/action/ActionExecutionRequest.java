package cn.ai.live.edgeagent.action;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record ActionExecutionRequest(
        String requestId,
        String target,
        String actionCode,
        String actionName,
        ActionType actionType,
        String assetPath,
        long durationMs,
        boolean loop,
        TransitionType transition,
        int priority,
        JsonNode parameters,
        Instant createdAt
) {
    public static ActionExecutionRequest from(ActionCommand command) {
        return new ActionExecutionRequest(
                "al-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                ActionTargets.normalizeOrDefault(command.target()),
                command.actionCode(),
                command.actionName(),
                command.actionType(),
                command.assetPath(),
                command.durationMs(),
                command.loop(),
                command.transition(),
                command.priority(),
                command.parameters(),
                Instant.now());
    }

    public ActionCommand toCommand() {
        return new ActionCommand(actionCode, actionName, target, actionType, assetPath, durationMs, loop, transition, priority, parameters);
    }
}
