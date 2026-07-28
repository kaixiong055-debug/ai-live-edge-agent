package cn.ai.live.edgeagent.command;

import java.time.Instant;

public record CommandConfigSnapshot(
        CommandConfig config,
        String status,
        Instant lastLoadedAt,
        String lastError,
        long version
) {
    public int commandCount() {
        return config == null ? 0 : config.getCommands().size();
    }
}
