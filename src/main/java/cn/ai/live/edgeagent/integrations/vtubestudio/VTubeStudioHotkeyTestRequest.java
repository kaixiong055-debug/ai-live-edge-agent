package cn.ai.live.edgeagent.integrations.vtubestudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public record VTubeStudioHotkeyTestRequest(String hotkeyId, String hotkeyName) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JsonNode toParameters() {
        Map<String, String> values = new LinkedHashMap<>();
        if (hotkeyId != null) {
            values.put("hotkeyId", hotkeyId);
        }
        if (hotkeyName != null) {
            values.put("hotkeyName", hotkeyName);
        }
        return MAPPER.valueToTree(values);
    }
}
