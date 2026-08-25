package com.campusguard.moderation.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.moderation.rule.ModerationRule;
import com.campusguard.moderation.rule.RuleProvider;
import com.campusguard.moderation.rule.RuleSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dangerous response is not the mangled one.
 *
 * <p>Broken JSON fails loudly wherever it lands. What needs checking is the
 * response that parses perfectly and carries a confidence of 1.7 or a rule that
 * was never written: those reach the database as facts and then appear in a
 * moderator's queue as though someone had established them.
 */
class VerdictParserTest {

    private final VerdictParser parser = new VerdictParser(new ObjectMapper(), rules());

    @Test
    void acceptsAWellFormedVerdict() {
        ModerationVerdict verdict = parser.parse("""
                {"decision":"REMOVE","confidence":0.91,"rationale":"Direct personal attack.","ruleCodes":["ABUSE"]}
                """);

        assertThat(verdict.decision()).isEqualTo(ModerationDecision.REMOVE);
        assertThat(verdict.confidence()).isEqualTo(0.91);
        assertThat(verdict.ruleCodes()).containsExactly("ABUSE");
    }

    @Test
    void rejectsAConfidenceOutsideTheUnitInterval() {
        assertThatThrownBy(() -> parser.parse("""
                {"decision":"REMOVE","confidence":1.7,"rationale":"Sure about this.","ruleCodes":["ABUSE"]}
                """))
                .isInstanceOf(InvalidVerdictException.class)
                .hasMessageContaining("between 0 and 1");
    }

    @Test
    void rejectsADecisionThatIsNotOneOfTheThree() {
        assertThatThrownBy(() -> parser.parse("""
                {"decision":"DELETE_IMMEDIATELY","confidence":0.9,"rationale":"x","ruleCodes":["ABUSE"]}
                """))
                .isInstanceOf(InvalidVerdictException.class)
                .hasMessageContaining("ALLOW, REMOVE or ESCALATE");
    }

    /**
     * A model that cites a rule nobody wrote has misread the rule set. Dropping
     * the invention silently would leave a verdict that still looks reasonable
     * and is grounded in nothing.
     */
    @Test
    void rejectsARuleCodeThatDoesNotExist() {
        assertThatThrownBy(() -> parser.parse("""
                {"decision":"REMOVE","confidence":0.8,"rationale":"x","ruleCodes":["HARASSMENT_TIER_2"]}
                """))
                .isInstanceOf(InvalidVerdictException.class)
                .hasMessageContaining("HARASSMENT_TIER_2");
    }

    @Test
    void rejectsARemovalThatNamesNoRule() {
        assertThatThrownBy(() -> parser.parse("""
                {"decision":"REMOVE","confidence":0.8,"rationale":"Felt wrong.","ruleCodes":[]}
                """))
                .isInstanceOf(InvalidVerdictException.class)
                .hasMessageContaining("at least one rule code");
    }

    @Test
    void rejectsAnEmptyRationale() {
        assertThatThrownBy(() -> parser.parse("""
                {"decision":"ALLOW","confidence":0.8,"rationale":"   ","ruleCodes":[]}
                """))
                .isInstanceOf(InvalidVerdictException.class)
                .hasMessageContaining("rationale");
    }

    @Test
    void rejectsSomethingThatIsNotJsonAtAll() {
        assertThatThrownBy(() -> parser.parse("I think this post is probably fine."))
                .isInstanceOf(InvalidVerdictException.class)
                .hasMessageContaining("not valid JSON");
    }

    /**
     * Models wrap JSON in a markdown fence despite being told not to. That is a
     * packaging problem rather than a schema problem, so it is tolerated; every
     * field inside is still checked.
     */
    @Test
    void toleratesAMarkdownFenceAroundTheJson() {
        ModerationVerdict verdict = parser.parse("""
                ```json
                {"decision":"ALLOW","confidence":0.7,"rationale":"Ordinary post.","ruleCodes":[]}
                ```
                """);

        assertThat(verdict.decision()).isEqualTo(ModerationDecision.ALLOW);
    }

    private RuleProvider rules() {
        return () -> List.of(
                new ModerationRule("ABUSE", "Abuse", "Personal attacks.", RuleSeverity.HIGH, List.of("idiot")),
                new ModerationRule("SPAM", "Spam", "Advertising.", RuleSeverity.LOW, List.of("buy now")));
    }
}
