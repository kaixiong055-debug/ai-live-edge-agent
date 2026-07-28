package cn.ai.live.edgeagent.renderer;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RendererWebSocketGateway gateway;

    public WebSocketConfig(RendererWebSocketGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gateway, "/ws/renderer").setAllowedOrigins("http://127.0.0.1:18081", "http://localhost:18081");
    }
}
