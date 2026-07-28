package cn.ai.live.edgeagent.command;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ai.live.edgeagent.asr.SpeechRecognitionResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandMatcherTest {
    @Test
    void shouldMatchCommandWhenWakeWordAndKeywordExist() {
        CommandMatchResult result = new CommandMatcher(new MutableClock()).match(finalText("小助手，给大家比个心！"), config());
        assertThat(result.matched()).isTrue();
        assertThat(result.command().getCode()).isEqualTo("heart");
    }

    @Test
    void shouldNormalizeSpacesAndCommonPunctuation() {
        assertThat(CommandMatcher.normalizeText(" 小助手，hello! 比 个 心。 ")).isEqualTo("小助手hello比个心");
    }

    @Test
    void shouldMissWhenWakeWordMissing() {
        CommandMatchResult result = new CommandMatcher(new MutableClock()).match(finalText("给大家比心"), config());
        assertThat(result.matched()).isFalse();
        assertThat(result.reason()).isEqualTo("wake-word-missing");
    }

    @Test
    void shouldRespectCooldown() {
        MutableClock clock = new MutableClock();
        CommandMatcher matcher = new CommandMatcher(clock);
        CommandMatchResult first = matcher.match(finalText("小助手比心"), config());
        clock.plusMillis(1000);
        CommandMatchResult second = matcher.match(finalText("小助手来一个比心"), config());
        assertThat(first.matched()).isTrue();
        assertThat(second.reason()).isEqualTo("cooldown");
    }

    @Test
    void shouldPreventDuplicateFinalResult() {
        CommandMatcher matcher = new CommandMatcher(new MutableClock());
        assertThat(matcher.match(finalText("小助手比心"), config()).matched()).isTrue();
        assertThat(matcher.match(finalText("小助手比心"), config()).reason()).isEqualTo("duplicate-final");
    }

    @Test
    void shouldIgnoreNonFinalRecognitionResult() {
        CommandMatchResult result = new CommandMatcher(new MutableClock())
                .match(new SpeechRecognitionResult("小助手比心", false, "voice-1", Instant.now()), config());
        assertThat(result.reason()).isEqualTo("not-final");
    }

    private SpeechRecognitionResult finalText(String text) {
        return new SpeechRecognitionResult(text, true, "voice-1", Instant.now());
    }

    private CommandConfig config() {
        CommandDefinition heart = new CommandDefinition();
        heart.setCode("heart");
        heart.setName("比心");
        heart.setKeywords(List.of("比心", "比个心", "来一个比心"));
        heart.setCooldownMs(3000);
        heart.setPriority(100);
        CommandConfig config = new CommandConfig();
        config.setWakeWords(List.of("小助手", "小先锋"));
        config.setCommands(List.of(heart));
        return config;
    }

    private static class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-28T00:00:00Z");
        void plusMillis(long millis) { instant = instant.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneId.systemDefault(); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
