package cn.ai.live.edgeagent.action;

import cn.ai.live.edgeagent.runtime.ActionSource;
import java.time.Clock;

public record ActionExecutionContext(ActionSource source, Clock clock) {
    public static ActionExecutionContext of(ActionSource source) {
        return new ActionExecutionContext(source, Clock.systemDefaultZone());
    }
}
