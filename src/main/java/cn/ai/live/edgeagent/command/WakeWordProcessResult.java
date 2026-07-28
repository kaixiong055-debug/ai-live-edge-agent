package cn.ai.live.edgeagent.command;

/**
 * 唤醒词处理结果。
 *
 * @param allowed        是否允许继续匹配命令
 * @param wakeWordMatched 是否命中了唤醒词
 * @param matchedWakeWord 命中的唤醒词文本（未命中为 null）
 * @param commandText    用于命令匹配的文本（已移除唤醒词和多余标点）
 * @param mode           当前使用的唤醒词模式
 */
public record WakeWordProcessResult(
        boolean allowed,
        boolean wakeWordMatched,
        String matchedWakeWord,
        String commandText,
        WakeWordMode mode
) {
    public static WakeWordProcessResult allowedDirect(String commandText, WakeWordMode mode) {
        return new WakeWordProcessResult(true, false, null, commandText, mode);
    }

    public static WakeWordProcessResult allowedWithWakeWord(String matchedWakeWord, String commandText, WakeWordMode mode) {
        return new WakeWordProcessResult(true, true, matchedWakeWord, commandText, mode);
    }

    public static WakeWordProcessResult blocked(WakeWordMode mode) {
        return new WakeWordProcessResult(false, false, null, null, mode);
    }

    public static WakeWordProcessResult onlyWakeWord(String matchedWakeWord, WakeWordMode mode) {
        return new WakeWordProcessResult(true, true, matchedWakeWord, "", mode);
    }
}
