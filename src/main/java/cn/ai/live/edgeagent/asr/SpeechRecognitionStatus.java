package cn.ai.live.edgeagent.asr;

public enum SpeechRecognitionStatus {
    STOPPED,
    STARTING,
    READY,
    CONNECTING,
    CONNECTED,
    MISCONFIGURED,
    MODEL_MISSING,
    NATIVE_LIBRARY_ERROR,
    UNAVAILABLE,
    ERROR,
    DISABLED
}
