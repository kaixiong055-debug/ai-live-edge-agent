package cn.ai.live.edgeagent.asr;

@FunctionalInterface
public interface SpeechRecognitionListener {
    void onResult(SpeechRecognitionResult result);
}
