package cn.ai.live.edgeagent.command;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ai.live.edgeagent.action.ActionType;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldParseOldCommandsJsonFormat() throws Exception {
        Path file = tempDir.resolve("old.json");
        Files.writeString(file, """
                {"wakeWords":["小助手"],"commands":[{"code":"heart","name":"比心","keywords":["比心"],"cooldownMs":1,"priority":2,"enabled":true}]}
                """);
        CommandConfig config = load(file);
        assertThat(config.getCommands().get(0).getActionType()).isEqualTo(ActionType.SHOW_IMAGE);
        assertThat(config.getCommands().get(0).getDurationMs()).isEqualTo(5000);
    }

    @Test
    void shouldParseNewCommandsJsonFormat() throws Exception {
        Path file = tempDir.resolve("new.json");
        Files.writeString(file, """
                {"wakeWords":["小助手"],"commands":[{"actionCode":"wave","actionName":"挥手","keywords":["挥手"],"actionType":"PLAY_WEBM","assetPath":"wave.webm","durationMs":2000,"loop":true,"transition":"FADE","priority":8}]}
                """);
        CommandDefinition command = load(file).getCommands().get(0);
        assertThat(command.getCode()).isEqualTo("wave");
        assertThat(command.getName()).isEqualTo("挥手");
        assertThat(command.getActionType()).isEqualTo(ActionType.PLAY_WEBM);
        assertThat(command.isLoop()).isTrue();
    }

    private CommandConfig load(Path file) {
        AiLiveProperties properties = new AiLiveProperties();
        properties.getCommand().setConfigPath(file.toString());
        return new CommandConfigLoader(new ObjectMapper(), properties).load();
    }
}
