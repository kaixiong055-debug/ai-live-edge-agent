package cn.ai.live.edgeagent.integrations.vtubestudio;

public enum VTubeStudioConnectionStatus {
    DISABLED,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHORIZATION_REQUIRED,
    AUTHORIZING,
    AUTHENTICATING,
    READY,
    NO_MODEL,
    RECONNECTING,
    ERROR,
    STOPPED
}
