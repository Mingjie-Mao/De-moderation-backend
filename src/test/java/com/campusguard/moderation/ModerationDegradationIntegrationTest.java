package com.campusguard.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.audit.AuditEntry;
import com.campusguard.audit.AuditEntryRepository;
import com.campusguard.audit.AuditLogger;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.engine.ModerationEngine;
import com.campusguard.moderation.engine.ai.AiInvocation;
import com.campusguard.moderation.engine.ai.AiInvocationRecorder;
import com.campusguard.moderation.engine.ai.AiInvocationRepository;
import com.campusguard.moderation.engine.ai.AiProperties;
import com.campusguard.moderation.engine.ai.ChatCompletionPort;
import com.campusguard.moderation.engine.ai.GeminiModerationEngine;
import com.campusguard.moderation.engine.ai.InvocationStatus;
import com.campusguard.moderation.engine.ai.ModelCallException;
import com.campusguard.moderation.engine.ai.ModerationPromptV1;
import com.campusguard.moderation.engine.ai.VerdictParser;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.report.CreateReportRequest;
import com.campusguard.report.ReportReason;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The claim this phase has to earn: with the model unreachable, moderation keeps
 * working.
 *
 * <p>The configured engine here is a real {@link GeminiModerationEngine} whose
 * only fault is that its provider refuses every call, which is exactly what an
 * expired or missing API key looks like from inside the process. Nothing is
 * mocked above the transport, so the path under test is the production one.
 */
@Import(ModerationDegradationIntegrationTest.UnreachableModelConfig.class)
@TestPropertySource(properties = "campusguard.moderation.engine=gemini-unreachable/v1")
class ModerationDegradationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ModerationCaseRepository caseRepository;

    @Autowired
    private ModerationWorker worker;

    @Autowired
    private AuditEntryRepository auditEntries;

    @Autowired
    private AiInvocationRepository aiInvocations;

    @Test
    void keepsModeratingWhenTheModelRefusesEveryCall() throws Exception {
        User author = newUser();
        UUID postId = createPost(author, "You are an idiot and everyone knows it");

        report(newUser(), postId).andExpect(status().isCreated());
        drainQueue(postId);

        ModerationCase judged = openCaseFor(postId);

        // Still reviewed, still with a usable verdict, and the rule engine is
        // named as its author rather than the model that never answered.
        assertThat(judged.getStatus()).isEqualTo(CaseStatus.AWAITING_REVIEW);
        assertThat(judged.getEngine()).isEqualTo("keyword-v1");
        assertThat(judged.getDecision()).isEqualTo(ModerationDecision.REMOVE);
        assertThat(judged.getRuleCodes()).contains("ABUSE");

        List<String> actions = auditEntries
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.POST, postId)
                .stream()
                .map(AuditEntry::getAction)
                .toList();

        assertThat(actions).contains(AuditLogger.ANALYSIS_FAILED, AuditLogger.ENGINE_DEGRADED);
    }

    /**
     * A failed call is as worth recording as a successful one: the failure rate is
     * the number that says whether the fallback is load-bearing or decorative, and
     * it cannot be reconstructed from anything else afterwards.
     */
    @Test
    void recordsTheFailedCallAgainstTheCase() throws Exception {
        UUID postId = createPost(newUser(), "Ordinary enough content");
        report(newUser(), postId).andExpect(status().isCreated());
        drainQueue(postId);

        UUID caseId = openCaseFor(postId).getId();
        List<AiInvocation> invocations = aiInvocations.findByCaseIdOrderByAttemptAsc(caseId);

        assertThat(invocations).hasSize(1);
        assertThat(invocations.getFirst().getStatus()).isEqualTo(InvocationStatus.ERROR);
        assertThat(invocations.getFirst().getError()).contains("API key not valid");
        assertThat(invocations.getFirst().getEngine()).isEqualTo("gemini-unreachable/v1");
        assertThat(invocations.getFirst().getPromptVersion()).isEqualTo(ModerationPromptV1.VERSION);
        // A refused credential is not retried; the circuit breaker handles repetition.
        assertThat(invocations.getFirst().getAttempt()).isEqualTo(1);
    }

    private void drainQueue(UUID targetId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (openCaseFor(targetId).getStatus() != CaseStatus.QUEUED) {
                return;
            }
            worker.runOnce();
        }
        throw new AssertionError("Case for " + targetId + " never left the queue.");
    }

    private ModerationCase openCaseFor(UUID targetId) {
        return caseRepository
                .findOpenByTarget(TargetType.POST, targetId)
                .orElseThrow(() -> new AssertionError("No open case for post " + targetId));
    }

    private org.springframework.test.web.servlet.ResultActions report(User reporter, UUID postId) throws Exception {
        return mockMvc.perform(post("/api/reports")
                .header("Authorization", bearer(reporter))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.ABUSE))));
    }

    private UUID createPost(User author, String body) throws Exception {
        String response = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", body))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    @TestConfiguration
    static class UnreachableModelConfig {

        /**
         * Substituted at the transport boundary rather than at the engine, so the
         * prompt, the parser, the retry policy and the invocation recording all
         * still run for real. Only the network is fake.
         */
        @Bean
        ModerationEngine unreachableGeminiEngine(
                ModerationPromptV1 prompt,
                VerdictParser parser,
                AiInvocationRecorder recorder,
                AiProperties properties) {

            ChatCompletionPort refusesEverything = new ChatCompletionPort() {
                @Override
                public String modelName() {
                    return "gemini-unreachable";
                }

                @Override
                public CompletionResult complete(String systemPrompt, String userPrompt) {
                    throw new ModelCallException(InvocationStatus.ERROR, "API key not valid. Please pass a valid API key.");
                }
            };

            return new GeminiModerationEngine(refusesEverything, prompt, parser, recorder, properties);
        }
    }
}
