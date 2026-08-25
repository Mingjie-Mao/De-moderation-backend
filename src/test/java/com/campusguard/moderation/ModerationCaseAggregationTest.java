package com.campusguard.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.admin.CaseDecisionRequest;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.report.CreateReportRequest;
import com.campusguard.report.ReportReason;
import com.campusguard.report.ReportService;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Several reports about one thing have to become one case, one analysis and one
 * decision. These tests are about what happens when those reports arrive at the
 * same instant, which is the part application code cannot get right on its own.
 */
class ModerationCaseAggregationTest extends AbstractIntegrationTest {

    @Autowired
    private ModerationCaseRepository caseRepository;

    @Autowired
    private ReportService reportService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ModerationWorker worker;

    @Test
    void foldsSeveralReportsOnOneTargetIntoASingleCase() throws Exception {
        UUID postId = createPost(newUser());

        report(newUser(), postId).andExpect(status().isCreated());
        report(newUser(), postId).andExpect(status().isCreated());
        report(newUser(), postId).andExpect(status().isCreated());

        assertThat(caseRowsFor(postId)).isEqualTo(1);
        assertThat(caseRepository.findOpenByTarget(TargetType.POST, postId).orElseThrow().getReportCount())
                .isEqualTo(3);
    }

    /**
     * Two reports on the same content, submitted at the same moment.
     *
     * <p>Both transactions read no open case before either writes, so nothing in
     * the service can tell them apart. The partial unique index decides which
     * insert survives, the other one's {@code ON CONFLICT DO NOTHING} affects no
     * rows, and it then reads the winner's case.
     *
     * <p>Without the index this test produces two cases, two analyses, and two
     * bills from whichever engine is running.
     */
    @Test
    void producesOneCaseWhenTwoReportsRaceOnTheSameTarget() throws Exception {
        UUID postId = createPost(newUser());

        User first = newUser();
        User second = newUser();

        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<?>> submissions = new ArrayList<>();
            for (User reporter : List.of(first, second)) {
                submissions.add(pool.submit(() -> {
                    startLine.await();
                    return reportService.create(
                            reporter.getId(),
                            new CreateReportRequest(TargetType.POST, postId, ReportReason.ABUSE));
                }));
            }

            startLine.countDown();
            for (Future<?> submission : submissions) {
                submission.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(caseRowsFor(postId)).isEqualTo(1);
        assertThat(caseRepository.findOpenByTarget(TargetType.POST, postId).orElseThrow().getReportCount())
                .isEqualTo(2);
    }

    /**
     * The uniqueness only covers open cases. Content reported again after an
     * earlier case was closed deserves a fresh case rather than a collision with
     * a decision already taken.
     */
    @Test
    void opensAFreshCaseWhenContentIsReportedAgainAfterResolution() throws Exception {
        User admin = newAdmin();
        UUID postId = createPost(newUser());

        report(newUser(), postId).andExpect(status().isCreated());
        UUID firstCaseId = caseRepository.findOpenByTarget(TargetType.POST, postId).orElseThrow().getId();
        worker.runOnce();

        assertThat(caseRepository.findById(firstCaseId).orElseThrow().getStatus())
                .isEqualTo(CaseStatus.AWAITING_REVIEW);

        mockMvc.perform(post("/api/admin/moderation-cases/{id}/decision", firstCaseId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CaseDecisionRequest(FinalAction.NONE, "Nothing wrong with it."))))
                .andExpect(status().isOk());

        report(newUser(), postId).andExpect(status().isCreated());

        UUID secondCaseId = caseRepository.findOpenByTarget(TargetType.POST, postId).orElseThrow().getId();
        assertThat(secondCaseId).isNotEqualTo(firstCaseId);
        assertThat(caseRowsFor(postId)).isEqualTo(2);
    }

    private int caseRowsFor(UUID targetId) {
        Integer rows = new JdbcTemplate(dataSource)
                .queryForObject(
                        "select count(*) from moderation_cases where target_type = 'POST' and target_id = ?",
                        Integer.class,
                        targetId);
        return rows == null ? 0 : rows;
    }

    private org.springframework.test.web.servlet.ResultActions report(User reporter, UUID postId) throws Exception {
        return mockMvc.perform(post("/api/reports")
                .header("Authorization", bearer(reporter))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.ABUSE))));
    }

    private UUID createPost(User author) throws Exception {
        String response = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Ordinary body text."))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(JsonPath.read(response, "$.id"));
    }
}
