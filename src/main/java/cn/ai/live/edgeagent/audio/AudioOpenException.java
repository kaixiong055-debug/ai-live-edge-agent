package cn.ai.live.edgeagent.audio;

public class AudioOpenException extends Exception {
    private final MicrophoneStatus status;

    public AudioOpenException(MicrophoneStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public MicrophoneStatus status() {
        return status;
    }
}
