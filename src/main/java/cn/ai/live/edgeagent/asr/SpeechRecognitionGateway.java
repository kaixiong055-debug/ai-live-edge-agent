package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.audio.PcmAudioFrame;
import org.springframework.stereotype.Component;

@Component
public class SpeechRecognitionGateway implements SpeechRecognitionProvider {
    private final SpeechRecognitionProvider delegate;

    public SpeechRecognitionGateway(SpeechRecognitionProviderFactory factory) {
        this.delegate = factory.current();
    }

    @Override
    public void addListener(SpeechRecognitionListener listener) {
        delegate.addListener(listener);
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void acceptPcm(byte[] pcm) {
        delegate.acceptPcm(pcm);
    }

    @Override
    public void sendAudio(PcmAudioFrame frame) {
        delegate.sendAudio(frame);
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public String getStatus() {
        return delegate.getStatus();
    }

    @Override
    public SpeechRecognitionProviderType getProviderType() {
        return delegate.getProviderType();
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }
}
