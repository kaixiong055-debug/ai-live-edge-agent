package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.config.AiLiveProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class SpeechRecognitionProviderFactory {
    private final AiLiveProperties properties;
    private final ObjectProvider<SherpaOnnxSpeechRecognitionProvider> sherpa;
    private final ObjectProvider<TencentSpeechRecognitionProvider> tencent;
    private final ObjectProvider<DisabledSpeechRecognitionProvider> disabled;
    private final ObjectProvider<UnavailableFunasrSpeechRecognitionProvider> funasr;

    public SpeechRecognitionProviderFactory(AiLiveProperties properties,
                                            ObjectProvider<SherpaOnnxSpeechRecognitionProvider> sherpa,
                                            ObjectProvider<TencentSpeechRecognitionProvider> tencent,
                                            ObjectProvider<DisabledSpeechRecognitionProvider> disabled,
                                            ObjectProvider<UnavailableFunasrSpeechRecognitionProvider> funasr) {
        this.properties = properties;
        this.sherpa = sherpa;
        this.tencent = tencent;
        this.disabled = disabled;
        this.funasr = funasr;
    }

    public SpeechRecognitionProvider current() {
        if (!properties.getAsr().isEnabled()) {
            return disabled.getObject();
        }
        return switch (properties.getAsr().getProvider()) {
            case SHERPA_ONNX -> sherpa.getObject();
            case TENCENT -> tencent.getObject();
            case FUNASR -> funasr.getObject();
            case DISABLED -> disabled.getObject();
        };
    }
}
