package cn.ai.live.edgeagent.command;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class CommandConfig {
    private List<String> wakeWords = new ArrayList<>();
    private List<CommandDefinition> commands = new ArrayList<>();
}
