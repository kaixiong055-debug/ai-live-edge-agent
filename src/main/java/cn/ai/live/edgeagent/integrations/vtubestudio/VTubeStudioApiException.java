package cn.ai.live.edgeagent.integrations.vtubestudio;

public class VTubeStudioApiException extends RuntimeException {
    private final String errorCode;

    public VTubeStudioApiException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
