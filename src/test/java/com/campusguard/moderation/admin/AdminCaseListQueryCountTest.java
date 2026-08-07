package com.campusguard.moderation.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.CaseStatus;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationWorker;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.report.CreateReportRequest;
import com.campusguard.report.ReportReason;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Whether listing resolved cases costs one query or one per row.
 *
 * <p>Asserted by counting rather than by reading the code, because the answer
 * turns on whether Hibernate serves an identifier from a lazy proxy without
 * initialising it, and that is exactly the kind of thing everyone is confident
 * about and half of them are wrong.
 *
 * <p>The assertion compares two page sizes instead of pinning an absolute number,
 * so it stays true when an unrelated query is added and still fails the moment
 * cost starts scaling with rows.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class AdminCaseListQueryCountTest extends AbstractIntegrationTest {

    @Autowired
    private AdminModerationService adminService;

    @Autowired
    private ModerationCaseRepository caseRepository;

    @Autowired
    private ModerationWorker worker;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void listingResolvedCasesDoesNotCostAQueryPerRow() throws Exception {
        User admin = newAdmin();

        for (int i = 0; i < 6; i++) {
            resolveOneCase(admin);
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        adminService.list(CaseStatus.RESOLVED, PageRequest.of(0, 1));
        long forOneRow = statistics.getPrepareStatementCount();

        statistics.clear();
        adminService.list(CaseStatus.RESOLVED, PageRequest.of(0, 5));
        long forFiveRows = statistics.getPrepareStatementCount();

        // Reading decidedBy.getId() off the lazy proxy takes the identifier from
        // the foreign key column already in hand, so five rows cost what one does.
        assertThat(forFiveRows)
                .as("query count grew from %d to %d when the page grew from 1 row to 5", forOneRow, forFiveRows)
                .isEqualTo(forOneRow);
    }

    private void resolveOneCase(User admin) throws Exception {
        UUID postId = createPost(newUser());

        mockMvc.perform(post("/api/reports")
                        .header("Authorization", bearer(newUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.ABUSE))))
                .andExpect(status().isCreated());

        for (int attempt = 0; attempt < 20; attempt++) {
            CaseStatus status = caseRepository
                    .findOpenByTarget(TargetType.POST, postId)
                    .orElseThrow()
                    .getStatus();
            if (status != CaseStatus.QUEUED) {
                break;
            }
            worker.runOnce();
        }

        UUID caseId = caseRepository.findOpenByTarget(TargetType.POST, postId).orElseThrow().getId();

        mockMvc.perform(post("/api/admin/moderation-cases/{id}/decision", caseId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CaseDecisionRequest(FinalAction.NONE, null))))
                .andExpect(status().isOk());
    }

    private UUID createPost(User author) throws Exception {
        String response = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Ordinary body."))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(JsonPath.read(response, "$.id"));
    }
}
