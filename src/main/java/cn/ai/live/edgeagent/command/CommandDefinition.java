package cn.ai.live.edgeagent.command;

import cn.ai.live.edgeagent.action.ActionTargets;
import cn.ai.live.edgeagent.action.ActionType;
import cn.ai.live.edgeagent.action.TransitionType;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommandDefinition {
    @JsonAlias("actionCode")
    private String code;
    @JsonAlias("actionName")
    private String name;
    private List<String> keywords = new ArrayList<>();
    private long cooldownMs = 3000;
    private int priority = 0;
    private boolean enabled = true;
    private String target = ActionTargets.MEDIA;
    private ActionType actionType = ActionType.SHOW_IMAGE;
    @JsonIgnore
    private boolean actionTypeExplicit;
    private String assetPath;
    private long durationMs = 5000;
    private boolean loop = false;
    private TransitionType transition = TransitionType.FADE;
    private JsonNode parameters;

    public void setActionType(ActionType actionType) {
        this.actionType = actionType;
        this.actionTypeExplicit = true;
    }
}
