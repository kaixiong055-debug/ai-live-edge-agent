package cn.ai.live.edgeagent.renderer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
public class RendererWebSocketGateway extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;
    private final ExecutorService rendererSendExecutor;

    public RendererWebSocketGateway(ObjectMapper objectMapper, ExecutorService rendererSendExecutor) {
        this.objectMapper = objectMapper;
        this.rendererSendExecutor = rendererSendExecutor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.removeIf(existing -> existing.getId().equals(session.getId()));
        sessions.add(session);
        log.info("Renderer 已连接: sessionId={}, count={}", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Renderer 已断开: sessionId={}, count={}", session.getId(), sessions.size());
    }

    public void broadcast(RendererMessage message) {
        if (sessions.isEmpty()) {
            log.info("当前没有 Renderer 连接，跳过广播: type={}", message.type());
            return;
        }
        rendererSendExecutor.submit(() -> sendToOpenSessions(message));
    }

    private void sendToOpenSessions(RendererMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (Exception ex) {
            log.error("Renderer 消息序列化失败", ex);
            return;
        }
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                session.sendMessage(new TextMessage(payload));
            } catch (IOException ex) {
                sessions.remove(session);
                log.warn("发送 Renderer 消息失败，已移除 session: {}", session.getId(), ex);
            }
        }
    }

    public int connectionCount() {
        return sessions.size();
    }

    public Map<String, Object> status() {
        return Map.of("connections", sessions.size());
    }
}
