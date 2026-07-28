package cn.ai.live.edgeagent.action;

public interface ActionExecutor {
    ActionExecutionResult execute(ActionCommand command);
}
