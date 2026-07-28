package cn.ai.live.edgeagent.action;

import cn.ai.live.edgeagent.command.CommandDefinition;

public record ActionCommand(
        String actionCode,
        String actionName,
        ActionType actionType,
        String assetPath,
        long durationMs,
        boolean loop,
        TransitionType transition,
        int priority
) {
    public static ActionCommand from(CommandDefinition definition) {
        return new ActionCommand(
                definition.getCode(),
                definition.getName(),
                definition.getActionType(),
                definition.getAssetPath(),
                definition.getDurationMs(),
                definition.isLoop(),
                definition.getTransition(),
                definition.getPriority());
    }

    public static ActionCommand clear() {
        return new ActionCommand("clear", "清空", ActionType.CLEAR, null, 0, false, TransitionType.NONE, Integer.MAX_VALUE);
    }
}
