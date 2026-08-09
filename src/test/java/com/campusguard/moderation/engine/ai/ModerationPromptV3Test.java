package com.campusguard.moderation.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.moderation.rule.ModerationRule;
import com.campusguard.moderation.rule.RuleProvider;
import com.campusguard.moderation.rule.RuleSeverity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * v3 claims to be v2 plus a rationale-language instruction, and that claim is
 * what makes a score comparison between the two mean anything. If someone edits
 * the classification wording in one of them, the comparison silently stops
 * measuring the language instruction and starts measuring an unknown mixture —
 * so the claim is pinned here rather than left to a code review.
 */
class ModerationPromptV3Test {

    private final RuleProvider rules = () -> List.of(
            new ModerationRule("ABUSE", "Abuse", "Personal attacks.", RuleSeverity.HIGH, List.of("idiot")));

    private final ModerationPromptV2 v2 = new ModerationPromptV2(rules);
    private final ModerationPromptV3 v3 = new ModerationPromptV3(rules);

    @Test
    void isVersionThree() {
        assertThat(v3.version()).isEqualTo("v3");
        assertThat(v3.version()).isNotEqualTo(v2.version());
    }

    /** The whole of v2's prompt is still there, untouched and in order. */
    @Test
    void keepsEveryWordOfTheJudgementWording() {
        assertThat(v3.system()).startsWith(v2.system());
    }

    @Test
    void addsAnInstructionAboutTheRationaleLanguage() {
        String added = v3.system().substring(v2.system().length());

        assertThat(added).contains("same language as the content");
        assertThat(added).contains("Chinese content gets a Chinese rationale");
        // decision and ruleCodes are wire values the parser matches on, so the
        // instruction has to exempt them or a translated "ALLOW" would fail
        // validation on every non-English case.
        assertThat(added).contains("rationale only");
        assertThat(added).contains("decision and ruleCodes stay exactly as specified above, in English");
    }

    @Test
    void asksTheSameQuestionAboutTheContentItself() {
        assertThat(v3.user(GeminiModerationEngineTest.sampleRequest()))
                .isEqualTo(v2.user(GeminiModerationEngineTest.sampleRequest()));
    }

    @Test
    void rendersTheConfiguredRulesLikeItsPredecessor() {
        assertThat(v3.system()).contains("ABUSE (HIGH): Personal attacks.");
    }
}
