package cn.ai.live.edgeagent.audio;

public enum MicrophoneStatus {
    DETECTING,
    NO_DEVICE,
    PERMISSION_DENIED,
    DEVICE_BUSY,
    STARTING,
    RUNNING,
    FAILED,
    RETRYING,
    STOPPED
}
