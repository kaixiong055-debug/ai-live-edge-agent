package cn.ai.live.edgeagent.integrations.vtubestudio;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VTubeStudioHotkey(
        @JsonAlias({"hotkeyID", "hotkeyId"}) String hotkeyId,
        String name,
        String type,
        String description,
        String file,
        String onScreenButtonId,
        boolean duplicateName
) {
    public VTubeStudioHotkey withDuplicateName(boolean duplicate) {
        return new VTubeStudioHotkey(hotkeyId, name, type, description, file, onScreenButtonId, duplicate);
    }
}
