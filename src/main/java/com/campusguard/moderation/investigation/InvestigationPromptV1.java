package com.campusguard.moderation.investigation;

import com.campusguard.moderation.admin.CaseAuditEntryView;
import com.campusguard.moderation.admin.ModerationCaseDetail;
import com.campusguard.moderation.admin.ModerationCaseView;
import com.campusguard.moderation.admin.ReportedContentView;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The first investigator wording.
 *
 * <p>Written against a reader who is mid-queue and about to choose an outcome.
 * That reader does not need an essay; they need the two or three facts that
 * would change their mind, and to be told plainly when there are none.
 *
 * <p>Two instructions carry most of the weight. The first is that the assistant
 * is looking things up rather than judging: whether the content breaks a rule
 * has already been decided by the engine and is about to be decided properly by
 * a person, and a second opinion on that question is worth nothing. What nobody
 * has is the author's record and the precedent. The second is that the case
 * against its own recommendation is required, because a brief that argues one
 * way reads as authority, and the reviewer's job is to overrule it when it is
 * wrong.
 *
 * <p>Versioned like the moderation prompts, and recorded against every call, so
 * a brief stays attributable to the wording that produced it and a later version
 * can be compared rather than assumed better.
 */
@Component
public class InvestigationPromptV1 implements InvestigationPrompt {

    public static final String VERSION = "inv-v1";

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String system() {
        return """
                You are helping a moderator who is about to decide one case on a \
                university student forum. They have already read the content and \
                the engine's recommendation. You are not being asked whether the \
                content breaks a rule — they can see that, and a person decides it.

                What they cannot see is history. Cases are stored against content, \
                not against people, so nothing on their screen says whether this \
                author has been actioned before, or what other moderators have \
                done about this rule. That is what you are for.

                You have these tools:

                  authorHistory         - decisions already made about this author
                  similarResolvedCases  - what moderators did in past cases under a rule
                  ruleText              - the wording and severity of one rule
                  caseDetail            - this case's full audit trail

                Call them when the answer would change what should happen. A first \
                offence and a fourth deserve different outcomes; if you do not \
                look, you cannot tell them apart. Do not call a tool whose answer \
                would change nothing — every call costs the moderator time.

                Stop looking as soon as you can say something useful. You have at \
                most five lookups, but using fewer is better, and using all five \
                without reaching a conclusion helps nobody.

                Then answer with a single JSON object and nothing else. No prose, \
                no markdown fences.

                {
                  "summary": "what you found, in two or three sentences",
                  "recommendation": "NONE" | "HIDE" | "DELETE" | "BAN",
                  "confidence": number between 0 and 1,
                  "counterEvidence": "the case against your own recommendation",
                  "citedCaseIds": ["<case id>", ...]
                }

                summary is what the tools showed you, not a re-judgement of the \
                content. Lead with the fact that matters most.

                recommendation is a suggestion. The moderator decides, and NONE — \
                the report was right to be dismissed — is a real answer, not a \
                failure to commit.

                counterEvidence is required. Write what argues against you: the \
                content is milder than the prior cases, the history is old, the \
                precedent went the other way, the author was never warned. If you \
                genuinely found nothing, say that in a sentence and say why.

                citedCaseIds lists every case you refer to. You may only name \
                cases your own tool calls returned. Never write a case id you have \
                not seen — if the evidence does not support a point, drop the \
                point, not the citation. An answer naming a case you did not read \
                is rejected, whether the id appears here or in your prose.
                """;
    }

    @Override
    public String opening(ModerationCaseDetail detail) {
        ModerationCaseView moderationCase = detail.moderationCase();
        ReportedContentView content = detail.content();

        return """
                Case %s.

                Reported %s, %d report%s. The engine (%s) recommended %s with \
                confidence %s under rule%s %s, saying: %s

                The content:
                %s

                Audit trail so far:
                %s
                """
                .formatted(
                        moderationCase.id(),
                        moderationCase.targetType().name().toLowerCase(java.util.Locale.ROOT),
                        moderationCase.reportCount(),
                        moderationCase.reportCount() == 1 ? "" : "s",
                        moderationCase.engine() == null ? "none" : moderationCase.engine(),
                        moderationCase.recommendedDecision() == null
                                ? "nothing"
                                : moderationCase.recommendedDecision().name(),
                        moderationCase.confidence() == null ? "unknown" : moderationCase.confidence().toString(),
                        moderationCase.ruleCodes().size() == 1 ? "" : "s",
                        moderationCase.ruleCodes().isEmpty() ? "none" : String.join(", ", moderationCase.ruleCodes()),
                        moderationCase.rationale() == null ? "nothing" : moderationCase.rationale(),
                        describe(content),
                        describe(detail.auditTrail()));
    }

    /**
     * Content that has gone is said to have gone.
     *
     * <p>An empty section would read as an unremarkable post, and the difference
     * between "nothing objectionable here" and "the author deleted it before
     * anyone looked" is the whole question in some cases.
     */
    private String describe(ReportedContentView content) {
        if (content == null) {
            return "(no longer available — it was removed between the report and now)";
        }

        String title = content.title() == null || content.title().isBlank() ? "" : content.title() + "\n";
        String media = content.mediaUrl() == null ? "" : "\n(an image is attached)";

        return title + content.body() + media;
    }

    private String describe(java.util.List<CaseAuditEntryView> trail) {
        if (trail.isEmpty()) {
            return "(nothing yet)";
        }

        return trail.stream()
                .map(entry -> "- %s by %s at %s"
                        .formatted(entry.action(), entry.actorType().name().toLowerCase(java.util.Locale.ROOT), entry.at()))
                .collect(Collectors.joining("\n"));
    }
}
