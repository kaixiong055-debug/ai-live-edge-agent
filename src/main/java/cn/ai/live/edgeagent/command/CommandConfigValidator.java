package cn.ai.live.edgeagent.command;

import cn.ai.live.edgeagent.action.ActionType;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CommandConfigValidator {
    public void validate(CommandConfig config) {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < config.getCommands().size(); i++) {
            CommandDefinition command = config.getCommands().get(i);
            String label = "commands[" + i + "]" + (command.getCode() == null ? "" : "(" + command.getCode() + ")");
            if (blank(command.getCode())) {
                fail(label, "actionCode/code", "不能为空");
            }
            if (!codes.add(command.getCode())) {
                fail(label, "actionCode/code", "重复");
            }
            if (command.getActionType() == null) {
                fail(label, "actionType", "不支持或不能为空");
            }
            if (command.getDurationMs() < 0) {
                fail(label, "durationMs", "不能小于 0");
            }
            if (command.getPriority() < -10000 || command.getPriority() > 10000) {
                fail(label, "priority", "必须在 -10000 到 10000 之间");
            }
            if (command.isActionTypeExplicit() && requiresAsset(command.getActionType()) && blank(command.getAssetPath())) {
                fail(label, "assetPath", "素材动作必须配置 assetPath");
            }
        }
    }

    private boolean requiresAsset(ActionType type) {
        return type == ActionType.SHOW_IMAGE || type == ActionType.PLAY_GIF || type == ActionType.PLAY_WEBM;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void fail(String command, String field, String reason) {
        throw new IllegalArgumentException(command + " 字段 " + field + " 错误: " + reason);
    }
}
