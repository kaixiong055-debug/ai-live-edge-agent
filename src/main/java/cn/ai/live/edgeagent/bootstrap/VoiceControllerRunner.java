package cn.ai.live.edgeagent.bootstrap;

import cn.ai.live.edgeagent.action.ActionCommand;
import cn.ai.live.edgeagent.action.ActionDispatcher;
import cn.ai.live.edgeagent.asr.SpeechRecognitionProvider;
import cn.ai.live.edgeagent.audio.AudioDeviceService;
import cn.ai.live.edgeagent.audio.MicrophoneCaptureService;
import cn.ai.live.edgeagent.command.CommandConfig;
import cn.ai.live.edgeagent.command.CommandConfigLoader;
import cn.ai.live.edgeagent.command.CommandMatchResult;
import cn.ai.live.edgeagent.command.CommandMatcher;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import jakarta.annotation.PreDestroy;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class VoiceControllerRunner implements ApplicationRunner {
    private final AudioDeviceService audioDeviceService;
    private final MicrophoneCaptureService microphoneCaptureService;
    private final SpeechRecognitionProvider recognitionProvider;
    private final CommandConfigLoader commandConfigLoader;
    private final CommandMatcher commandMatcher;
    private final ActionDispatcher actionDispatcher;
    private final AiLiveProperties properties;

    public VoiceControllerRunner(AudioDeviceService audioDeviceService, MicrophoneCaptureService microphoneCaptureService,
                                 SpeechRecognitionProvider recognitionProvider, CommandConfigLoader commandConfigLoader,
                                 CommandMatcher commandMatcher, ActionDispatcher actionDispatcher, AiLiveProperties properties) {
        this.audioDeviceService = audioDeviceService;
        this.microphoneCaptureService = microphoneCaptureService;
        this.recognitionProvider = recognitionProvider;
        this.commandConfigLoader = commandConfigLoader;
        this.commandMatcher = commandMatcher;
        this.actionDispatcher = actionDispatcher;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        printMicrophones();
        CommandConfig commandConfig = commandConfigLoader.load();
        recognitionProvider.addListener(result -> {
            CommandMatchResult matchResult = commandMatcher.match(result, commandConfig);
            if (matchResult.matched()) {
                actionDispatcher.dispatch(ActionCommand.from(matchResult.command()));
            }
        });
        if (!properties.getAsr().isAutoStart()) {
            log.info("已关闭自动监听: ai-live.asr.auto-start=false");
            return;
        }
        recognitionProvider.start();
        microphoneCaptureService.start(properties.getAsr().getMicrophoneName(),
                properties.getAsr().getAudioFrameMillis(),
                recognitionProvider::sendAudio);
    }

    private void printMicrophones() {
        List<String> devices = audioDeviceService.listMicrophoneDevices();
        log.info("当前可用麦克风设备:");
        for (int i = 0; i < devices.size(); i++) {
            log.info("  [{}] {}", i, devices.get(i));
        }
    }

    @PreDestroy
    public void shutdown() {
        microphoneCaptureService.stop();
        recognitionProvider.stop();
    }
}
