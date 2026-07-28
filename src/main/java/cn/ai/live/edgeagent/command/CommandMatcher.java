package cn.ai.live.edgeagent.command;

import cn.ai.live.edgeagent.asr.SpeechRecognitionResult;
import java.time.Clock;
import java.util.Comparator;
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
    private final Clock clock;
    private final Map<String, Long> lastTriggeredAt = new ConcurrentHashMap<>();
    private volatile String lastFinalText;

    public CommandMatcher() {
        this(Clock.systemDefaultZone());
    }

    public CommandMatcher(Clock clock) {
        this.clock = clock;
    }

    public CommandMatchResult match(SpeechRecognitionResult result, CommandConfig config) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(config, "config");
        if (!result.finalResult()) {
            log.info("忽略非最终识别结果: {}", result.text());
            return CommandMatchResult.missed("not-final");
        }
        String normalized = normalizeText(result.text());
        if (normalized.isBlank()) {
            return CommandMatchResult.missed("blank");
        }
        if (normalized.equals(lastFinalText)) {
            log.info("忽略重复最终识别结果: {}", result.text());
            return CommandMatchResult.missed("duplicate-final");
        }
        lastFinalText = normalized;

        boolean wakeMatched = config.getWakeWords().stream()
                .map(CommandMatcher::normalizeText)
                .anyMatch(normalized::contains);
        if (!wakeMatched) {
            log.info("未命中唤醒词: {}", result.text());
            return CommandMatchResult.missed("wake-word-missing");
        }

        return config.getCommands().stream()
                .filter(CommandDefinition::isEnabled)
                .sorted(Comparator.comparingInt(CommandDefinition::getPriority).reversed())
                .filter(command -> matchesKeyword(normalized, command))
                .findFirst()
                .map(this::checkCooldown)
                .orElseGet(() -> CommandMatchResult.missed("command-missing"));
    }

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
}
