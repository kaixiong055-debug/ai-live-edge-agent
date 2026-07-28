package cn.ai.live.edgeagent.localapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ai.live.edgeagent.action.ActionDispatcher;
import cn.ai.live.edgeagent.assets.AssetService;
import cn.ai.live.edgeagent.audio.MicrophoneCaptureService;
import cn.ai.live.edgeagent.command.CommandConfig;
import cn.ai.live.edgeagent.command.CommandConfigLoader;
import cn.ai.live.edgeagent.command.CommandDefinition;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import cn.ai.live.edgeagent.renderer.RendererWebSocketGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalActionControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldReturnLocalHealthStatus() throws Exception {
        Files.createDirectories(tempDir.resolve("assets"));
        CommandConfig config = new CommandConfig();
        CommandDefinition command = new CommandDefinition();
        command.setCode("heart");
        config.setCommands(List.of(command));

        CommandConfigLoader loader = mock(CommandConfigLoader.class);
        when(loader.currentConfig()).thenReturn(config);
        AiLiveProperties properties = new AiLiveProperties();
        properties.getAssets().setRootPath(tempDir.resolve("assets").toString());
        MicrophoneCaptureService microphone = mock(MicrophoneCaptureService.class);
        when(microphone.isRunning()).thenReturn(false);
        RendererWebSocketGateway gateway = mock(RendererWebSocketGateway.class);
        when(gateway.connectionCount()).thenReturn(2);

        LocalActionController controller = new LocalActionController(loader, mock(ActionDispatcher.class), properties, microphone, gateway, new AssetService(properties));
        Map<String, Object> health = controller.health();

        assertThat(health.get("status")).isEqualTo("UP");
        assertThat(health.get("commandCount")).isEqualTo(1);
        assertThat(health.get("rendererConnections")).isEqualTo(2);
        assertThat(health.get("assetRootExists")).isEqualTo(true);
    }
}
