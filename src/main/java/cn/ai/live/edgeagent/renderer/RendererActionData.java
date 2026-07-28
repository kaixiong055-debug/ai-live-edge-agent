package cn.ai.live.edgeagent.renderer;

import cn.ai.live.edgeagent.action.ActionType;
import cn.ai.live.edgeagent.action.TransitionType;

public record RendererActionData(
        String actionCode,
        ActionType actionType,
        String assetUrl,
        long durationMs,
        boolean loop,
        TransitionType transition
) {
}
