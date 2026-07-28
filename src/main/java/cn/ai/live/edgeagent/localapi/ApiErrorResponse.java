package cn.ai.live.edgeagent.localapi;

import java.time.Instant;

public record ApiErrorResponse(boolean success, String errorCode, String message, Instant timestamp) {
    public static ApiErrorResponse of(String errorCode, String message) {
        return new ApiErrorResponse(false, errorCode, message, Instant.now());
    }
}
