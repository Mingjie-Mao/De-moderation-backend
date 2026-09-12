package com.campusguard.evaluation.investigation;

import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationDecision;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One situation an investigation might be asked about.
 *
 * <p>Not a piece of text, which is what the engine's evaluation set is made of.
 * An investigation exists to answer questions the content cannot: has this
 * author been here before, and what has been done about this rule. So a sample
 * has to be a situation — the content, the record behind it, and the precedent
 * around it — and the harness builds all three before it asks anything.
 *
 * @param priorActions how this author's earlier cases were resolved, oldest
 *     first. Empty is a clean record, which is a case worth testing rather than
 *     a missing field.
 * @param priorAgeDays how long ago those decisions were, one per prior. Lets a
 *     record that has aged out of the ninety-day window be told apart from one
 *     that has not, which is a distinction the assistant is supposed to make and
 *     no other sample would catch.
 * @param precedentActions what other moderators did under this rule, seeded
 *     immediately before the run so that this scenario's precedent is the
 *     precedent the tool returns. A mixed list is deliberately different from a
 *     unanimous one: it is how a scenario asks whether the assistant reads the
 *     spread or just follows the first thing it sees.
 * @param expected the outcome a careful moderator would most likely reach. Used
 *     for the tally, and never for pass or fail on its own.
 * @param acceptable every outcome a careful moderator could defend, including
 *     {@code expected}. Moderation rarely has one right answer, and scoring
 *     against a single value would make a defensible brief look wrong.
 * @param mustCite what the brief has to refer to. A recommendation that happens
 *     to be right while citing nothing is a guess that landed, and over a set
 *     this size the two are indistinguishable without this.
 * @param note why this scenario is labelled the way it is. Only read by people,
 *     and the thing that makes a disputed label settleable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InvestigationScenario(
        String id,
        String shape,
        String title,
        String body,
        String ruleCode,
        ModerationDecision engineDecision,
        List<FinalAction> priorActions,
        List<Integer> priorAgeDays,
        List<FinalAction> precedentActions,
        FinalAction expected,
        List<FinalAction> acceptable,
        MustCite mustCite,
        String note) {

    public InvestigationScenario {
        priorActions = priorActions == null ? List.of() : List.copyOf(priorActions);
        priorAgeDays = priorAgeDays == null ? List.of() : List.copyOf(priorAgeDays);
        precedentActions = precedentActions == null ? List.of() : List.copyOf(precedentActions);
        acceptable = acceptable == null || acceptable.isEmpty()
                ? List.of(expected)
                : List.copyOf(acceptable);
        mustCite = mustCite == null ? MustCite.NOTHING : mustCite;
    }

    /** Which of the fixture's cases a brief has to name for its reasoning to be grounded. */
    public enum MustCite {

        /** Nothing in particular — a clean author with no precedent worth leaning on. */
        NOTHING,

        /** This author's own earlier decisions. A brief about a repeat offender that does not name them is guessing. */
        PRIORS,

        /** The precedent under this rule. */
        PRECEDENT,

        /** Both, which is what a case with a record and a precedent turns on. */
        BOTH
    }
}
