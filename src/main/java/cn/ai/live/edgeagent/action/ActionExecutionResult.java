package cn.ai.live.edgeagent.action;

public record ActionExecutionResult(boolean accepted, String reason) {
    public static ActionExecutionResult ok() {
        return new ActionExecutionResult(true, "accepted");
    }

    public static ActionExecutionResult rejected(String reason) {
        return new ActionExecutionResult(false, reason);
    }
}
