package cn.ai.live.edgeagent.action;

import java.time.Duration;
import java.time.Instant;

public record ActionExecutionResult(
        boolean success,
        ActionExecutionStatus status,
        String target,
        ActionType actionType,
        String actionCode,
        String requestId,
        String message,
        String errorCode,
        Instant startedAt,
        Instant completedAt,
        long latencyMs
) {
    public boolean accepted() {
        return success || status == ActionExecutionStatus.ACCEPTED;
    }

    public String reason() {
        return errorCode != null ? errorCode : message;
    }

    public static ActionExecutionResult ok() {
        return success(ActionTargets.MEDIA, null, null, null, "accepted", Instant.now());
    }

    public static ActionExecutionResult rejected(String reason) {
        return failure(ActionTargets.MEDIA, null, null, null, ActionExecutionStatus.FAILED, reason, reason, Instant.now());
    }

    public static ActionExecutionResult accepted(ActionExecutionRequest request, String message) {
        return finish(request, ActionExecutionStatus.ACCEPTED, true, message, null, request.createdAt());
    }

    public static ActionExecutionResult success(ActionExecutionRequest request, String message, Instant startedAt) {
        return finish(request, ActionExecutionStatus.SUCCESS, true, message, null, startedAt);
    }

    public static ActionExecutionResult success(String target, ActionType actionType, String actionCode,
                                                String requestId, String message, Instant startedAt) {
        Instant completedAt = Instant.now();
        return new ActionExecutionResult(true, ActionExecutionStatus.SUCCESS, target, actionType, actionCode, requestId,
                message, null, startedAt, completedAt, Duration.between(startedAt, completedAt).toMillis());
    }

    public static ActionExecutionResult failure(ActionExecutionRequest request, ActionExecutionStatus status,
                                                String errorCode, String message, Instant startedAt) {
        return failure(request.target(), request.actionType(), request.actionCode(), request.requestId(), status, errorCode, message, startedAt);
    }

    public static ActionExecutionResult failure(String target, ActionType actionType, String actionCode, String requestId,
                                                ActionExecutionStatus status, String errorCode, String message, Instant startedAt) {
        Instant completedAt = Instant.now();
        return new ActionExecutionResult(false, status, target, actionType, actionCode, requestId, message, errorCode,
                startedAt, completedAt, Duration.between(startedAt, completedAt).toMillis());
    }

    private static ActionExecutionResult finish(ActionExecutionRequest request, ActionExecutionStatus status, boolean success,
                                                String message, String errorCode, Instant startedAt) {
        Instant completedAt = Instant.now();
        return new ActionExecutionResult(success, status, request.target(), request.actionType(), request.actionCode(),
                request.requestId(), message, errorCode, startedAt, completedAt,
                Duration.between(startedAt, completedAt).toMillis());
    }
}
