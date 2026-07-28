package cn.ai.live.edgeagent.command;

/**
 * 唤醒词模式。
 *
 * <ul>
 *   <li>{@link #REQUIRED} — 必须包含唤醒词才会尝试匹配命令。</li>
 *   <li>{@link #OPTIONAL} — 可以直接说命令，也可以带上唤醒词。</li>
 *   <li>{@link #DISABLED} — 完全不检查唤醒词。</li>
 * </ul>
 */
public enum WakeWordMode {
    REQUIRED,
    OPTIONAL,
    DISABLED;

    /**
     * 从配置字符串安全解析，非法值默认回退到 {@link #OPTIONAL}。
     */
    public static WakeWordMode fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return OPTIONAL;
        }
        try {
            return WakeWordMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OPTIONAL;
        }
    }
}
