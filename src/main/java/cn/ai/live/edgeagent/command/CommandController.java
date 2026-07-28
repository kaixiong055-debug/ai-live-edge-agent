package cn.ai.live.edgeagent.command;

import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/local-api/commands")
@ConditionalOnProperty(prefix = "ai-live.local-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommandController {
    private final CommandConfigManager manager;

    public CommandController(CommandConfigManager manager) {
        this.manager = manager;
    }

    @GetMapping
    public Map<String, Object> commands() {
        CommandConfigSnapshot snapshot = manager.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", snapshot.status());
        body.put("lastLoadedAt", snapshot.lastLoadedAt());
        body.put("lastError", snapshot.lastError() == null ? "" : snapshot.lastError());
        body.put("version", snapshot.version());
        body.put("commands", snapshot.config().getCommands());
        return body;
    }

    @PostMapping("/reload")
    public CommandConfigSnapshot reload() {
        return manager.reload();
    }
}
