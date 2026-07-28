package cn.ai.live.edgeagent.action;

public enum ActionExecutionStatus {
    SUCCESS,
    ACCEPTED,
    TARGET_DISABLED,
    TARGET_UNAVAILABLE,
    UNAUTHENTICATED,
    INVALID_ACTION,
    ACTION_NOT_FOUND,
    TIMEOUT,
    FAILED
}
