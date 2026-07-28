package cn.ai.live.edgeagent.command;

import cn.ai.live.edgeagent.action.ActionType;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CommandConfigLoader {
    private final ObjectMapper objectMapper;
    private final AiLiveProperties properties;
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
    private volatile CommandConfig currentConfig = new CommandConfig();

    public CommandConfigLoader(ObjectMapper objectMapper, AiLiveProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public CommandConfig load() {
        try (InputStream inputStream = openConfigStream()) {
            CommandConfig config = objectMapper.readValue(inputStream, CommandConfig.class);
            validateAndDefault(config);
            currentConfig = config;
            log.info("已加载口令配置: wakeWords={}, commands={}", config.getWakeWords().size(), config.getCommands().size());
            return config;
        } catch (Exception ex) {
            throw new IllegalStateException("读取口令配置失败: " + properties.getCommand().getConfigPath(), ex);
        }
    }

    public CommandConfig currentConfig() {
        return currentConfig;
    }

    private InputStream openConfigStream() throws Exception {
        String path = properties.getCommand().getConfigPath();
        if (path.startsWith("classpath:")) {
            return resourceLoader.getResource(path).getInputStream();
        }
        return Files.newInputStream(Path.of(path));
    }

    private void validateAndDefault(CommandConfig config) {
        for (CommandDefinition command : config.getCommands()) {
            if (command.getCode() == null || command.getCode().isBlank()) {
                throw new IllegalArgumentException("命令配置错误: code/actionCode 不能为空");
            }
            if (command.getName() == null || command.getName().isBlank()) {
                command.setName(command.getCode());
            }
            if (command.getActionType() == null) {
                command.setActionType(ActionType.SHOW_IMAGE);
            }
            if (command.getPriority() == 0) {
                command.setPriority(0);
            }
            if (command.getCooldownMs() < 0) {
                throw new IllegalArgumentException("命令 " + command.getCode() + " 字段 cooldownMs 不能小于 0");
            }
        }
    }
}
