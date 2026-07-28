package cn.ai.live.edgeagent.integrations.vtubestudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class VTubeStudioJsonSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VTubeStudioJsonSupport() {
    }

    static String requestIdFrom(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode id = root.get("requestID");
            return id == null ? null : id.asText(null);
        } catch (Exception ex) {
            return null;
        }
    }
}
