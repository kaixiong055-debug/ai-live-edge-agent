package cn.ai.live.edgeagent.bootstrap;

import cn.ai.live.edgeagent.action.ActionCommand;
import cn.ai.live.edgeagent.action.ActionDispatcher;
import cn.ai.live.edgeagent.asr.AsrSessionCoordinator;
import cn.ai.live.edgeagent.asr.SpeechRecognitionGateway;
import cn.ai.live.edgeagent.audio.AudioDeviceService;
import cn.ai.live.edgeagent.command.CommandConfigManager;
import cn.ai.live.edgeagent.command.CommandMatchResult;
import cn.ai.live.edgeagent.command.CommandMatcher;
import cn.ai.live.edgeagent.runtime.ActionSource;
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
    private final SpeechRecognitionGateway recognitionProvider;
    private final CommandConfigManager commandConfigManager;
    private final CommandMatcher commandMatcher;
    private final ActionDispatcher actionDispatcher;
    private final AsrSessionCoordinator asrSessionCoordinator;

    public VoiceControllerRunner(AudioDeviceService audioDeviceService,
                                 SpeechRecognitionGateway recognitionProvider, CommandConfigManager commandConfigManager,
                                 CommandMatcher commandMatcher, ActionDispatcher actionDispatcher,
                                 AsrSessionCoordinator asrSessionCoordinator) {
        this.audioDeviceService = audioDeviceService;
        this.recognitionProvider = recognitionProvider;
        this.commandConfigManager = commandConfigManager;
        this.commandMatcher = commandMatcher;
        this.actionDispatcher = actionDispatcher;
        this.asrSessionCoordinator = asrSessionCoordinator;
    }

    @Override
    public void run(ApplicationArguments args) {
        printMicrophones();
        recognitionProvider.addListener(result -> {
            CommandMatchResult matchResult = commandMatcher.match(result, commandConfigManager.currentConfig());
            if (matchResult.matched()) {
                actionDispatcher.dispatch(ActionCommand.from(matchResult.command()), ActionSource.ASR);
            }
        });

        // 根据 auto-connect 配置自动连接或等待手动连接
        asrSessionCoordinator.onBoot();
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
        asrSessionCoordinator.shutdown();
    }
}
