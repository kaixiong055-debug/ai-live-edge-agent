package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.audio.PcmAudioFrame;

public interface SpeechRecognitionProvider {

    void addListener(SpeechRecognitionListener listener);

    void start();

    void sendAudio(PcmAudioFrame frame);

    void stop();
}
