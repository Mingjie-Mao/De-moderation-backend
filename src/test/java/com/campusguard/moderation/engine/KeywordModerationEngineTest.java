package com.campusguard.moderation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.common.TargetType;
import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.rule.ModerationRule;
import com.campusguard.moderation.rule.RuleProvider;
import com.campusguard.moderation.rule.RuleSeverity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests with a stub rule provider: the engine's behaviour is a pure function
 * of its rules and its input, and standing up Postgres to check that a word
 * boundary works would only make the same assertion slower.
 */
class KeywordModerationEngineTest {

    @Test
    void removesContentMatchingAHighSeverityRule() {
        KeywordModerationEngine engine = engineWith(
                rule("ABUSE", RuleSeverity.HIGH, List.of("idiot")));

        ModerationVerdict verdict = engine.evaluate(request("You are an idiot"));

        assertThat(verdict.decision()).isEqualTo(ModerationDecision.REMOVE);
        assertThat(verdict.ruleCodes()).containsExactly("ABUSE");
        assertThat(verdict.confidence()).isGreaterThan(0.8);
    }

    /**
     * Advertising wording turns up in legitimate posts often enough that removing
     * on a term alone would be worse than asking a person.
     */
    @Test
    void escalatesRatherThanRemovingOnALowSeverityRule() {
        KeywordModerationEngine engine = engineWith(
                rule("SPAM", RuleSeverity.LOW, List.of("limited offer")));

        assertThat(engine.evaluate(request("A limited offer on tickets")).decision())
                .isEqualTo(ModerationDecision.ESCALATE);
    }

    @Test
    void takesTheWorstSeverityWhenSeveralRulesMatch() {
        KeywordModerationEngine engine = engineWith(
                rule("SPAM", RuleSeverity.LOW, List.of("buy now")),
                rule("ABUSE", RuleSeverity.HIGH, List.of("idiot")));

        ModerationVerdict verdict = engine.evaluate(request("buy now you idiot"));

        assertThat(verdict.decision()).isEqualTo(ModerationDecision.REMOVE);
        assertThat(verdict.ruleCodes()).containsExactlyInAnyOrder("SPAM", "ABUSE");
    }

    /**
     * An ASCII term is anchored to word boundaries, so a rule aimed at one word
     * does not fire inside a longer, innocent one.
     */
    @Test
    void doesNotMatchAnAsciiTermInsideALongerWord() {
        KeywordModerationEngine engine = engineWith(
                rule("ABUSE", RuleSeverity.HIGH, List.of("id")));

        assertThat(engine.evaluate(request("idiotic identity idea")).decision())
                .isEqualTo(ModerationDecision.ALLOW);
    }

    /**
     * Chinese is written without spaces, so a boundary anchor would never fire on
     * a Chinese term and substring matching is the only thing that works.
     */
    @Test
    void matchesANonAsciiTermAsASubstring() {
        KeywordModerationEngine engine = engineWith(
                rule("ABUSE", RuleSeverity.HIGH, List.of("傻逼")));

        assertThat(engine.evaluate(request("楼主傻逼说的什么")).decision())
                .isEqualTo(ModerationDecision.REMOVE);
    }

    @Test
    void judgesTitleAndBodyTogether() {
        KeywordModerationEngine engine = engineWith(
                rule("ABUSE", RuleSeverity.HIGH, List.of("idiot")));

        ModerationRequest request = ModerationRequest.of(
                TargetType.POST, UUID.randomUUID(), "You idiot", "Perfectly ordinary body text.", UUID.randomUUID());

        assertThat(engine.evaluate(request).decision()).isEqualTo(ModerationDecision.REMOVE);
    }

    @Test
    void isCaseInsensitive() {
        KeywordModerationEngine engine = engineWith(
                rule("ABUSE", RuleSeverity.HIGH, List.of("idiot")));

        assertThat(engine.evaluate(request("You IDIOT")).decision()).isEqualTo(ModerationDecision.REMOVE);
    }

    /**
     * The engine cannot see wording it has not been given, and its confidence on a
     * clean verdict says so. Overstating this is what would make the baseline look
     * better than it is, which would in turn understate what a model adds.
     */
    @Test
    void allowsWithLowConfidenceWhenNothingMatches() {
        KeywordModerationEngine engine = engineWith(
                rule("ABUSE", RuleSeverity.HIGH, List.of("idiot")));

        ModerationVerdict verdict = engine.evaluate(request("You absolute moron"));

        assertThat(verdict.decision()).isEqualTo(ModerationDecision.ALLOW);
        assertThat(verdict.confidence()).isLessThanOrEqualTo(0.5);
        assertThat(verdict.ruleCodes()).isEmpty();
    }

    private KeywordModerationEngine engineWith(ModerationRule... rules) {
        RuleProvider provider = () -> List.of(rules);
        return new KeywordModerationEngine(provider);
    }

    private ModerationRule rule(String code, RuleSeverity severity, List<String> terms) {
        return new ModerationRule(code, code, "Test rule " + code, severity, terms);
    }

    private ModerationRequest request(String body) {
        return ModerationRequest.of(TargetType.POST, UUID.randomUUID(), null, body, UUID.randomUUID());
    }
}
