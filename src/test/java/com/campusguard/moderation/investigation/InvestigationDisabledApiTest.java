package com.campusguard.moderation.investigation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.post.Post;
import com.campusguard.post.PostRepository;
import com.campusguard.user.User;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How the endpoints behave in the configuration almost every deployment runs.
 *
 * <p>Off is the default, so this is what a reviewer meets: a clear sentence
 * rather than a 500 or a missing route. Its own class because whether a
 * {@code CaseInvestigator} bean exists is a property of the application context,
 * and one context cannot have it both ways.
 */
class InvestigationDisabledApiTest extends AbstractIntegrationTest {

    @Autowired
    private ModerationCaseRepository cases;

    @Autowired
    private PostRepository posts;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void saysTheAssistantIsNotEnabledRatherThanFailing() throws Exception {
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(newUser());

        mockMvc.perform(post("/api/admin/moderation-cases/{id}/investigate", caseId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Not enabled"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not enabled")));
    }

    /** Reading is still fine with the assistant off; there is simply never anything to read. */
    @Test
    void stillAnswersARequestForAnExistingBrief() throws Exception {
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(newUser());

        mockMvc.perform(get("/api/admin/moderation-cases/{id}/investigation", caseId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
    }

    private UUID awaitingReviewCase(User author) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Post post = posts.saveAndFlush(new Post(uniqueForumKey(), author, "A title", "A body"));

            cases.openCaseIfAbsent(TargetType.POST.name(), post.getId());
            UUID caseId = cases.findOpenCaseId(TargetType.POST, post.getId()).orElseThrow();

            ModerationCase moderationCase = cases.findById(caseId).orElseThrow();
            moderationCase.markAnalysing();
            moderationCase.recordVerdict(
                    "keyword-v1",
                    new ModerationVerdict(ModerationDecision.REMOVE, 0.8, "A test verdict.", List.of("ABUSE")));

            return cases.saveAndFlush(moderationCase).getId();
        });
    }
}
