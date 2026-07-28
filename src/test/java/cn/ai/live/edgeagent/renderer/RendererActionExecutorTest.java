package cn.ai.live.edgeagent.renderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ai.live.edgeagent.action.ActionCommand;
import cn.ai.live.edgeagent.action.ActionExecutionCoordinator;
import cn.ai.live.edgeagent.action.ActionType;
import cn.ai.live.edgeagent.action.TransitionType;
import cn.ai.live.edgeagent.assets.AssetService;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class RendererActionExecutorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldConvertImageGifAndWebmToRendererMessages() throws Exception {
        Files.writeString(tempDir.resolve("heart.png"), "png");
        Files.writeString(tempDir.resolve("welcome.gif"), "gif");
        Files.writeString(tempDir.resolve("wave.webm"), "webm");
        CapturingSession session = new CapturingSession();
        RendererActionExecutor executor = executor(session);

        executor.execute(new ActionCommand("heart", "比心", ActionType.SHOW_IMAGE, "heart.png", 5000, false, TransitionType.FADE, 100));
        executor.execute(new ActionCommand("welcome", "欢迎", ActionType.PLAY_GIF, "welcome.gif", 5000, false, TransitionType.FADE, 100));
        executor.execute(new ActionCommand("wave", "挥手", ActionType.PLAY_WEBM, "wave.webm", 5000, false, TransitionType.FADE, 100));

        assertPayload(session.messages.get(0), "heart", "SHOW_IMAGE", "/local-assets/heart.png");
        assertPayload(session.messages.get(1), "welcome", "PLAY_GIF", "/local-assets/welcome.gif");
        assertPayload(session.messages.get(2), "wave", "PLAY_WEBM", "/local-assets/wave.webm");
    }

    @Test
    void shouldSendHideAndClearMessages() throws Exception {
        CapturingSession session = new CapturingSession();
        RendererActionExecutor executor = executor(session);
        executor.execute(new ActionCommand("hide", "隐藏", ActionType.HIDE, null, 0, false, TransitionType.NONE, 1));
        executor.execute(ActionCommand.clear());
        assertThat(objectMapper.readTree(session.messages.get(0)).get("type").asText()).isEqualTo("HIDE_CURRENT");
        assertThat(objectMapper.readTree(session.messages.get(1)).get("type").asText()).isEqualTo("CLEAR_RENDERER");
    }

    @Test
    void shouldRejectMissingTraversalAndUnsupportedAssets() throws Exception {
        RendererActionExecutor executor = executor(new CapturingSession());
        assertThat(executor.execute(new ActionCommand("x", "x", ActionType.SHOW_IMAGE, "missing.png", 1, false, TransitionType.FADE, 1)).accepted()).isFalse();
        assertThat(executor.execute(new ActionCommand("x", "x", ActionType.SHOW_IMAGE, "../secret.png", 1, false, TransitionType.FADE, 1)).reason()).contains("traversal");
        Files.writeString(tempDir.resolve("bad.exe"), "bad");
        assertThat(executor.execute(new ActionCommand("x", "x", ActionType.SHOW_IMAGE, "bad.exe", 1, false, TransitionType.FADE, 1)).reason()).contains("unsupported");
    }

    @Test
    void shouldNotFailWhenNoRendererClient() throws Exception {
        RendererActionExecutor executor = executor(null);
        Files.writeString(tempDir.resolve("heart.png"), "png");
        assertThat(executor.execute(new ActionCommand("heart", "比心", ActionType.SHOW_IMAGE, "heart.png", 1, false, TransitionType.FADE, 1)).accepted()).isTrue();
    }

    @Test
    void shouldRegisterDuplicateSessionAndRemoveClosedSession() throws Exception {
        RendererWebSocketGateway gateway = new RendererWebSocketGateway(objectMapper, new DirectExecutorService());
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("same");
        gateway.afterConnectionEstablished(session);
        gateway.afterConnectionEstablished(session);
        assertThat(gateway.connectionCount()).isEqualTo(1);
        gateway.afterConnectionClosed(session, null);
        assertThat(gateway.connectionCount()).isZero();
    }

    @Test
    void shouldApplyPriorityRulesAndDurationTimeout() throws Exception {
        Files.writeString(tempDir.resolve("low.png"), "png");
        Files.writeString(tempDir.resolve("high.png"), "png");
        CapturingSession session = new CapturingSession();
        RendererActionExecutor executor = executor(session);
        assertThat(executor.execute(new ActionCommand("high", "高", ActionType.SHOW_IMAGE, "high.png", 80, false, TransitionType.FADE, 100)).accepted()).isTrue();
        assertThat(executor.execute(new ActionCommand("low", "低", ActionType.SHOW_IMAGE, "low.png", 80, false, TransitionType.FADE, 10)).reason()).isEqualTo("lower-priority");
        Thread.sleep(140);
        assertThat(session.messages).anySatisfy(payload -> {
            try {
                assertThat(objectMapper.readTree(payload).get("type").asText()).isEqualTo("HIDE_CURRENT");
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
    }

    private RendererActionExecutor executor(CapturingSession session) throws Exception {
        AiLiveProperties properties = new AiLiveProperties();
        properties.getAssets().setRootPath(tempDir.toString());
        RendererWebSocketGateway gateway = new RendererWebSocketGateway(objectMapper, new DirectExecutorService());
        if (session != null) {
            gateway.afterConnectionEstablished(session);
        }
        return new RendererActionExecutor(new AssetService(properties), gateway,
                new ActionExecutionCoordinator(Executors.newSingleThreadScheduledExecutor()));
    }

    private void assertPayload(String payload, String code, String type, String url) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        assertThat(root.get("type").asText()).isEqualTo("RENDER_ACTION");
        assertThat(root.get("eventId").asText()).isNotBlank();
        assertThat(root.get("data").get("actionCode").asText()).isEqualTo(code);
        assertThat(root.get("data").get("actionType").asText()).isEqualTo(type);
        assertThat(root.get("data").get("assetUrl").asText()).isEqualTo(url);
    }

    static class CapturingSession implements WebSocketSession {
        final List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public String getId() { return "capture"; }
        @Override public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message) { messages.add(((TextMessage) message).getPayload()); }
        @Override public boolean isOpen() { return true; }
        @Override public void close() {}
        @Override public void close(org.springframework.web.socket.CloseStatus status) {}
        @Override public java.net.URI getUri() { return null; }
        @Override public org.springframework.http.HttpHeaders getHandshakeHeaders() { return null; }
        @Override public java.util.Map<String, Object> getAttributes() { return java.util.Map.of(); }
        @Override public java.security.Principal getPrincipal() { return null; }
        @Override public java.net.InetSocketAddress getLocalAddress() { return null; }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public java.util.List<org.springframework.web.socket.WebSocketExtension> getExtensions() { return List.of(); }
    }

    static class DirectExecutorService extends AbstractExecutorService {
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
    }
}
