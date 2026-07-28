package cn.ai.live.edgeagent.command;

import cn.ai.live.edgeagent.asr.SpeechRecognitionResult;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CommandMatcher {
    private static final Pattern NOISE_PATTERN = Pattern.compile("[\\s，。！？、；：,.!?;:'\"“”‘’（）()【】\\[\\]《》<>-]+");

    /** 唤醒词后的标点分隔（仅用于唤醒词剥离阶段，比 NOISE_PATTERN 更保守） */
    private static final Pattern WAKE_WORD_SEPARATOR = Pattern.compile("[\\s，。！？、；：,.!?;:'\"“”‘’（）()【】\\[\\]《》<>-]+");

    private final Clock clock;
    private final AiLiveProperties properties;
    private final Map<String, Long> lastTriggeredAt = new ConcurrentHashMap<>();
    private volatile String lastFinalText;

    // ── Console 状态追踪 ──
    private volatile boolean lastWakeWordMatched;
    private volatile String lastMatchedWakeWord;
    private volatile String lastCommandText;
    private volatile String lastMatchedCommandCode;
    private volatile String lastMatchedKeyword;

    public CommandMatcher() {
        this(null, Clock.systemDefaultZone());
    }

    public CommandMatcher(AiLiveProperties properties) {
        this(properties, Clock.systemDefaultZone());
    }

    public CommandMatcher(AiLiveProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 测试用构造：仅 Clock，默认 OPTIONAL。 */
    CommandMatcher(Clock clock) {
        this.properties = null;
        this.clock = clock;
    }

    /** 测试用构造：直接指定模式。 */
    CommandMatcher(Clock clock, WakeWordMode wakeWordMode) {
        this.properties = null;
        this.clock = clock;
        this.testMode = wakeWordMode;
    }

    private volatile WakeWordMode testMode;

    public CommandMatchResult match(SpeechRecognitionResult result, CommandConfig config) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(config, "config");
        if (!result.finalResult()) {
            log.info("忽略非最终识别结果: {}", result.text());
            return CommandMatchResult.missed("not-final");
        }
        String originalText = result.text();
        String normalized = normalizeText(originalText);
        if (normalized.isBlank()) {
            return CommandMatchResult.missed("blank");
        }
        if (normalized.equals(lastFinalText)) {
            log.info("忽略重复最终识别结果: {}", originalText);
            return CommandMatchResult.missed("duplicate-final");
        }
        lastFinalText = normalized;

        // --- 唤醒词处理 ---
        WakeWordMode mode = effectiveMode();
        List<String> wakeWordsList = mergedWakeWords(config);
        WakeWordProcessResult wakeResult = processWakeWord(normalized, wakeWordsList, mode);

        // 更新追踪状态
        this.lastCommandText = normalized;
        this.lastWakeWordMatched = wakeResult.wakeWordMatched();
        this.lastMatchedWakeWord = wakeResult.matchedWakeWord();

        if (!wakeResult.allowed()) {
            log.info("命令被唤醒词规则拦截: mode={}, text={}", mode, originalText);
            return CommandMatchResult.missed("wake-word-missing");
        }

        String commandText = wakeResult.commandText();
        if (commandText == null || commandText.isBlank()) {
            log.info("唤醒词后没有有效命令内容: mode={}, wakeWord={}, text={}",
                    mode, wakeResult.matchedWakeWord(), originalText);
            return CommandMatchResult.missed("wake-word-no-command");
        }

        // --- 关键词匹配 ---
        CommandMatchResult matchResult = config.getCommands().stream()
                .filter(CommandDefinition::isEnabled)
                .sorted(Comparator.comparingInt(CommandDefinition::getPriority).reversed())
                .filter(command -> matchesKeyword(commandText, command))
                .findFirst()
                .map(this::checkCooldown)
                .orElseGet(() -> CommandMatchResult.missed("command-missing"));

        // 更新追踪状态
        if (matchResult.matched()) {
            this.lastMatchedCommandCode = matchResult.command().getCode();
            this.lastMatchedKeyword = findMatchedKeyword(commandText, matchResult.command());
        } else {
            this.lastMatchedCommandCode = null;
            this.lastMatchedKeyword = null;
        }

        // 成功日志
        if (matchResult.matched()) {
            CommandDefinition cmd = matchResult.command();
            log.info("命令匹配成功: originalText={}, commandText={}, wakeWordMode={}, wakeWordMatched={}, wakeWord={}, commandCode={}, commandName={}, matchedKeyword={}, actionType={}",
                    originalText, commandText, mode, wakeResult.wakeWordMatched(),
                    wakeResult.matchedWakeWord(), cmd.getCode(), cmd.getName(),
                    findMatchedKeyword(commandText, cmd), cmd.getActionType());
        } else if ("command-missing".equals(matchResult.reason())) {
            log.info("未匹配到命令关键词: commandText={}, originalText={}", commandText, originalText);
        } else if ("cooldown".equals(matchResult.reason())) {
            log.info("命令处于冷却时间: commandText={}", commandText);
        } else if ("disabled".equals(matchResult.reason())) {
            log.info("命令已禁用: commandText={}", commandText);
        }

        return matchResult;
    }

    // ────────── 唤醒词处理 ──────────

    /**
     * 根据模式处理唤醒词。
     */
    WakeWordProcessResult processWakeWord(String normalized, List<String> wakeWords, WakeWordMode mode) {
        // DISABLED：直接通过
        if (mode == WakeWordMode.DISABLED) {
            return WakeWordProcessResult.allowedDirect(normalized, mode);
        }

        // 查找命中的唤醒词（优先句首，再查全文）
        String matchedWakeWord = findWakeWord(normalized, wakeWords);
        if (matchedWakeWord == null) {
            // REQUIRED 模式下没有唤醒词则拦截
            if (mode == WakeWordMode.REQUIRED) {
                return WakeWordProcessResult.blocked(mode);
            }
            // OPTIONAL 模式下没有唤醒词直接通过
            return WakeWordProcessResult.allowedDirect(normalized, mode);
        }

        // 命中了唤醒词，尝试剥离
        String stripped = stripWakeWord(normalized, matchedWakeWord);
        if (stripped.isBlank()) {
            // 只有唤醒词，没有命令内容
            return WakeWordProcessResult.onlyWakeWord(matchedWakeWord, mode);
        }

        return WakeWordProcessResult.allowedWithWakeWord(matchedWakeWord, stripped, mode);
    }

    /**
     * 在标准化文本中查找唤醒词。
     * 优先匹配文本开头，其次匹配全文。
     */
    private String findWakeWord(String normalized, List<String> wakeWords) {
        if (wakeWords == null || wakeWords.isEmpty()) {
            return null;
        }
        return wakeWords.stream()
                .map(CommandMatcher::normalizeText)
                .filter(w -> !w.isBlank())
                .filter(normalized::startsWith)
                .findFirst()
                .orElseGet(() -> wakeWords.stream()
                        .map(CommandMatcher::normalizeText)
                        .filter(w -> !w.isBlank())
                        .filter(normalized::contains)
                        .findFirst()
                        .orElse(null));
    }

    /**
     * 从标准化文本中剥离唤醒词及紧随的标点/空格。
     * 仅剥离句首匹配的唤醒词。
     * 示例："小伴休息一下" → "休息一下"、"小伴，休息一下"（已标准化为"小伴休息一下"）→ "休息一下"
     */
    private String stripWakeWord(String normalized, String wakeWord) {
        String normalizedWakeWord = normalizeText(wakeWord);
        if (!normalized.startsWith(normalizedWakeWord)) {
            return normalized;
        }
        String remainder = normalized.substring(normalizedWakeWord.length());
        // 去除紧跟的标点前缀
        return WAKE_WORD_SEPARATOR.matcher(remainder).replaceAll("").trim();
    }

    // ────────── Console 状态暴露 ──────────

    public boolean lastWakeWordMatched() {
        return lastWakeWordMatched;
    }

    public String lastMatchedWakeWord() {
        return lastMatchedWakeWord;
    }

    public String lastCommandText() {
        return lastCommandText;
    }

    public String lastMatchedCommandCode() {
        return lastMatchedCommandCode;
    }

    public String lastMatchedKeyword() {
        return lastMatchedKeyword;
    }

    public WakeWordMode effectiveWakeWordMode() {
        return effectiveMode();
    }

    public List<String> configuredWakeWords() {
        return properties != null && properties.getCommand() != null
                ? properties.getCommand().getWakeWords()
                : List.of();
    }

    // ────────── 辅助方法 ──────────

    public static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return NOISE_PATTERN.matcher(text).replaceAll("").trim();
    }

    private boolean matchesKeyword(String normalizedText, CommandDefinition command) {
        return command.getKeywords() != null && command.getKeywords().stream()
                .map(CommandMatcher::normalizeText)
                .anyMatch(normalizedText::contains);
    }

    private String findMatchedKeyword(String normalizedText, CommandDefinition command) {
        if (command.getKeywords() == null) {
            return null;
        }
        return command.getKeywords().stream()
                .filter(k -> normalizeText(k).length() > 0)
                .filter(k -> normalizedText.contains(normalizeText(k)))
                .findFirst()
                .orElse(null);
    }

    private CommandMatchResult checkCooldown(CommandDefinition command) {
        long now = clock.millis();
        Long previous = lastTriggeredAt.get(command.getCode());
        if (previous != null && now - previous < command.getCooldownMs()) {
            log.info("动作仍在冷却中: code={}", command.getCode());
            return CommandMatchResult.missed("cooldown");
        }
        lastTriggeredAt.put(command.getCode(), now);
        return CommandMatchResult.matched(command);
    }

    private WakeWordMode effectiveMode() {
        if (testMode != null) {
            return testMode;
        }
        if (properties != null && properties.getCommand() != null) {
            return properties.getCommand().effectiveWakeWordMode();
        }
        return WakeWordMode.OPTIONAL;
    }

    private List<String> mergedWakeWords(CommandConfig config) {
        if (properties != null && properties.getCommand() != null) {
            return properties.getCommand().mergedWakeWords(config.getWakeWords());
        }
        return config.getWakeWords();
    }
}
