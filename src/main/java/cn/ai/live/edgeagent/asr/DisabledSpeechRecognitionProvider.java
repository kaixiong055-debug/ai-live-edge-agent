package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.audio.PcmAudioFrame;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Lazy
@Component
public class DisabledSpeechRecognitionProvider implements SpeechRecognitionProvider {
    private final List<SpeechRecognitionListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void addListener(SpeechRecognitionListener listener) {
        listeners.add(listener);
    }

    @Override
    public void start() {
    }

    @Override
    public void sendAudio(PcmAudioFrame frame) {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getStatus() {
        return SpeechRecognitionStatus.DISABLED.name();
    }

    @Override
    public SpeechRecognitionProviderType getProviderType() {
        return SpeechRecognitionProviderType.DISABLED;
    }
}
