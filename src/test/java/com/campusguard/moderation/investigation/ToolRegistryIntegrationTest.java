package com.campusguard.moderation.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.CaseStatus;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.post.Post;
import com.campusguard.post.PostRepository;
import com.campusguard.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The boundary the investigator is allowed to operate inside.
 *
 * <p>Two properties are being pinned, and they pull in opposite directions. The
 * model must not be able to reach anything outside the tool set, and it must not
 * be stopped by its own ordinary mistakes — a wrong tool name or a missing
 * argument has to come back as something it can read and correct, because
 * otherwise every such slip becomes a failed investigation the reviewer sees.
 */
class ToolRegistryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private ModerationCaseRepository cases;

    @Autowired
    private PostRepository posts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void offersExactlyTheFourToolsAndNothingElse() {
        assertThat(registry.specs()).extracting(ToolSpec::name)
                .containsExactly("authorHistory", "caseDetail", "ruleText", "similarResolvedCases");
    }

    /**
     * The first thing to check about a whitelist is that it is one. A model can
     * write any name at all into a tool call, and the answer to a name that is
     * not a key has to be an answer rather than a dispatch.
     */
    @Test
    void refusesAToolThatDoesNotExistWithoutEndingTheInvestigation() {
        UUID caseId = awaitingReviewCase(newUser(), "ABUSE");

        ToolResult result = registry.execute(caseId, call("banUser", "{\"userId\":\"whoever\"}"));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("no tool named 'banUser'").contains("authorHistory");
        assertThat(result.disclosedCaseIds()).isEmpty();
    }

    /**
     * The message is the point. It is fed straight back to the model as the
     * result of its own call, and a model told which field it missed fixes it on
     * the next step, while one told "invalid request" guesses.
     */
    @Test
    void rejectsBadArgumentsWithSomethingTheModelCanActOn() {
        UUID caseId = awaitingReviewCase(newUser(), "ABUSE");

        assertThat(registry.execute(caseId, call("ruleText", "{}")).content())
                .isEqualTo("Field 'code' is required.");

        assertThat(registry.execute(caseId, call("ruleText", "{\"code\": 7}")).content())
                .isEqualTo("Field 'code' must be a string.");

        assertThat(registry.execute(caseId, call("ruleText", "{\"code\":\"NO_SUCH_RULE\"}")).content())
                .contains("no active rule with code 'NO_SUCH_RULE'")
                .contains("ABUSE");

        assertThat(registry.execute(caseId, call("similarResolvedCases", "{}")).content())
                .isEqualTo("Field 'ruleCode' is required.");
    }

    @Test
    void readsTheCaseUnderInvestigation() throws Exception {
        User author = newUser();
        UUID caseId = awaitingReviewCase(author, "ABUSE");

        ToolResult result = registry.execute(caseId, call("caseDetail", null));

        assertThat(result.error()).isFalse();
        JsonNode payload = mapper.readTree(result.content());
        assertThat(payload.at("/moderationCase/id").asText()).isEqualTo(caseId.toString());
        assertThat(payload.at("/moderationCase/status").asText()).isEqualTo("AWAITING_REVIEW");
        assertThat(result.disclosedCaseIds()).containsExactly(caseId);
    }

    @Test
    void readsTheAuthorsPriorDecisions() throws Exception {
        User author = newUser();
        UUID priorCase = resolvedCase(author, "ABUSE", FinalAction.HIDE);
        UUID caseId = awaitingReviewCase(author, "ABUSE");

        ToolResult result = registry.execute(caseId, call("authorHistory", null));

        JsonNode payload = mapper.readTree(result.content());
        assertThat(payload.get("username").asText()).isEqualTo(author.getUsername());
        assertThat(payload.get("resolvedCaseCount").asInt()).isEqualTo(1);
        assertThat(payload.at("/decisions/0/caseId").asText()).isEqualTo(priorCase.toString());
        assertThat(payload.at("/decisions/0/finalAction").asText()).isEqualTo("HIDE");

        // The case being investigated is not among its own precedents, and only
        // the prior one may be cited.
        assertThat(result.disclosedCaseIds()).containsExactly(priorCase);
    }

    /**
     * An author with no history and an author whose history could not be read
     * must not produce the same answer, because a model handed an empty list
     * reads it as a clean record either way.
     */
    @Test
    void saysSoWhenTheAuthorCannotBeIdentified() throws Exception {
        UUID caseId = awaitingReviewCase(newUser(), "ABUSE");
        ModerationCase subject = cases.findById(caseId).orElseThrow();

        // The content is gone rather than hidden, which is what a hard delete
        // upstream would leave behind.
        jdbcTemplate.update("delete from posts where id = ?", subject.getTargetId());

        JsonNode payload = mapper.readTree(registry.execute(caseId, call("authorHistory", null)).content());

        assertThat(payload.get("note").asText()).contains("not evidence of a clean record");
        assertThat(payload.get("resolvedCaseCount").asInt()).isZero();
    }

    @Test
    void readsPrecedentForARuleAndCapsIt() throws Exception {
        String code = uniqueRuleCode();
        User author = newUser();
        for (int i = 0; i < 7; i++) {
            resolvedCase(author, code, FinalAction.HIDE);
        }
        UUID caseId = awaitingReviewCase(author, code);

        ToolResult result =
                registry.execute(caseId, call("similarResolvedCases", "{\"ruleCode\":\"" + code + "\"}"));

        JsonNode payload = mapper.readTree(result.content());
        assertThat(payload.get("count").asInt()).isEqualTo(5);
        assertThat(result.disclosedCaseIds()).hasSize(5);
    }

    /**
     * The read-only guarantee, checked at the database rather than by reading the
     * tools and taking their word for it.
     *
     * <p>Two transactions rather than one, because a refused write leaves
     * PostgreSQL's transaction block aborted and every later statement in it is
     * rejected on those grounds instead of on its own merits. Separating them is
     * not a workaround: the first asserts that this configuration really does
     * refuse writes, without which the second would pass just as happily if the
     * transaction were not read-only at all.
     */
    @Test
    void aReadOnlyTransactionHereReallyDoesRefuseWrites() {
        UUID caseId = awaitingReviewCase(newUser(), "ABUSE");

        assertThatThrownBy(() -> readOnlyTransaction().executeWithoutResult(status -> jdbcTemplate.update(
                        "update moderation_cases set report_count = report_count + 1 where id = ?", caseId)))
                .hasMessageContaining("read-only");
    }

    @Test
    void everyToolRunsInsideAReadOnlyTransaction() {
        User author = newUser();
        resolvedCase(author, "ABUSE", FinalAction.HIDE);
        UUID caseId = awaitingReviewCase(author, "ABUSE");

        readOnlyTransaction().executeWithoutResult(status -> {
            List<ToolCall> everyTool = List.of(
                    call("caseDetail", null),
                    call("authorHistory", null),
                    call("similarResolvedCases", "{\"ruleCode\":\"ABUSE\"}"),
                    call("ruleText", "{\"code\":\"ABUSE\"}"));

            for (ToolCall toolCall : everyTool) {
                ToolResult result = registry.execute(caseId, toolCall);
                assertThat(result.error()).as("%s should have succeeded", toolCall.name()).isFalse();
            }
        });
    }

    private TransactionTemplate readOnlyTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    private ToolCall call(String name, String argumentsJson) {
        try {
            return new ToolCall(
                    "call_" + UUID.randomUUID().toString().substring(0, 8),
                    name,
                    argumentsJson == null ? null : mapper.readTree(argumentsJson));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Unique per test, because precedent is global and every other test's cases are in the same table. */
    private String uniqueRuleCode() {
        return "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private UUID awaitingReviewCase(User author, String ruleCode) {
        return caseFor(author, ruleCode, null);
    }

    private UUID resolvedCase(User author, String ruleCode, FinalAction action) {
        return caseFor(author, ruleCode, action);
    }

    /** @param action null leaves the case awaiting review, which is the only state an investigation runs in */
    private UUID caseFor(User author, String ruleCode, FinalAction action) {
        User admin = newAdmin();

        return new TransactionTemplate(transactionManager).execute(status -> {
            Post post = posts.saveAndFlush(new Post(uniqueForumKey(), author, "A title", "A body"));

            cases.openCaseIfAbsent(TargetType.POST.name(), post.getId());
            UUID caseId = cases.findOpenCaseId(TargetType.POST, post.getId()).orElseThrow();

            ModerationCase moderationCase = cases.findById(caseId).orElseThrow();
            moderationCase.markAnalysing();
            moderationCase.recordVerdict(
                    "test-engine",
                    new ModerationVerdict(
                            ModerationDecision.REMOVE, 0.9, "Recorded by a test.", List.of(ruleCode)));

            if (action != null) {
                moderationCase.resolve(admin, action);
            }

            ModerationCase saved = cases.saveAndFlush(moderationCase);
            assertThat(saved.getStatus())
                    .isEqualTo(action == null ? CaseStatus.AWAITING_REVIEW : CaseStatus.RESOLVED);
            return saved.getId();
        });
    }
}
