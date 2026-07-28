package cn.ai.live.edgeagent.action;

import cn.ai.live.edgeagent.command.CommandDefinition;
import com.fasterxml.jackson.databind.JsonNode;

public record ActionCommand(
        String actionCode,
        String actionName,
        String target,
        ActionType actionType,
        String assetPath,
        long durationMs,
        boolean loop,
        TransitionType transition,
        int priority,
        JsonNode parameters
) {
    public ActionCommand(String actionCode, String actionName, ActionType actionType, String assetPath,
                         long durationMs, boolean loop, TransitionType transition, int priority) {
        this(actionCode, actionName, ActionTargets.MEDIA, actionType, assetPath, durationMs, loop, transition, priority, null);
    }

    public static ActionCommand from(CommandDefinition definition) {
        return new ActionCommand(
                definition.getCode(),
                definition.getName(),
                definition.getTarget(),
                definition.getActionType(),
                definition.getAssetPath(),
                definition.getDurationMs(),
                definition.isLoop(),
                definition.getTransition(),
                definition.getPriority(),
                definition.getParameters());
    }

    public static ActionCommand clear() {
        return new ActionCommand("clear", "清空", ActionTargets.MEDIA, ActionType.CLEAR, null, 0, false, TransitionType.NONE,
                Integer.MAX_VALUE, null);
    }
}
