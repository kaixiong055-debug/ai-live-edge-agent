package cn.ai.live.edgeagent.command;

public record CommandMatchResult(boolean matched, CommandDefinition command, String reason) {
    public static CommandMatchResult matched(CommandDefinition command) {
        return new CommandMatchResult(true, command, "matched");
    }

    public static CommandMatchResult missed(String reason) {
        return new CommandMatchResult(false, null, reason);
    }
}
