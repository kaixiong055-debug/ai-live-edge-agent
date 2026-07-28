package cn.ai.live.edgeagent.renderer;

public record RendererMessage(String type, String eventId, long timestamp, Object data) {
}
