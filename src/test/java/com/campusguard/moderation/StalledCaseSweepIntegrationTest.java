package com.campusguard.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.TargetType;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.report.CreateReportRequest;
import com.campusguard.report.ReportReason;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * What happens to a case whose worker never came back.
 *
 * <p>The README calls this the thing that makes the queue durable rather than
 * merely asynchronous, which is a strong claim to leave to inspection. A process
 * killed between claiming a case and finishing it leaves a row in
 * {@code ANALYSING} that nothing else would ever look at again: not the claim
 * query, which only reads {@code QUEUED}, and not a person, because the report
 * was accepted and the reporter was told it would be reviewed.
 *
 * <p>The sweep has to be wrong in neither direction. Too slow and reports die
 * quietly; too eager and it hands a case to a second worker while the first is
 * still in the middle of a model call, which is the same content judged twice
 * and billed twice.
 */
class StalledCaseSweepIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ModerationCaseRepository caseRepository;

    @Autowired
    private ModerationCaseProcessor processor;

    @Autowired
    private ModerationWorker worker;

    @Test
    void returnsACaseWhoseWorkerNeverCameBack() throws Exception {
        UUID postId = reportedPost();

        // Claiming and then doing nothing is what a killed process looks like from
        // the database's side: the claim committed, the analysis never happened.
        List<UUID> claimed = processor.claimBatch(10);
        assertThat(claimed).contains(caseFor(postId).getId());
        assertThat(caseFor(postId).getStatus()).isEqualTo(CaseStatus.ANALYSING);

        // Zero rather than a slept-through five minutes: the threshold is the
        // argument, so the test can be honest about time instead of waiting for it.
        int requeued = processor.requeueStalled(Duration.ZERO);

        assertThat(requeued).isPositive();
        assertThat(caseFor(postId).getStatus()).isEqualTo(CaseStatus.QUEUED);
    }

    /**
     * Returned to the queue is only half the promise; the case has to actually get
     * judged afterwards. A row flipped back to QUEUED that no worker picks up is
     * the same lost report with a different status on it.
     */
    @Test
    void aRequeuedCaseIsPickedUpAndJudged() throws Exception {
        UUID postId = reportedPost();

        processor.claimBatch(10);
        processor.requeueStalled(Duration.ZERO);

        worker.runOnce();

        ModerationCase judged = caseFor(postId);
        assertThat(judged.getStatus()).isEqualTo(CaseStatus.AWAITING_REVIEW);
        assertThat(judged.getDecision()).isNotNull();
        assertThat(judged.getEngine()).isNotNull();
    }

    /**
     * The other direction, and the more expensive one to get wrong. A worker
     * holding a case for two seconds is working, not dead.
     */
    @Test
    void leavesAloneACaseSomebodyIsStillWorkingOn() throws Exception {
        UUID postId = reportedPost();

        processor.claimBatch(10);

        int requeued = processor.requeueStalled(Duration.ofMinutes(5));

        assertThat(requeued).isZero();
        assertThat(caseFor(postId).getStatus()).isEqualTo(CaseStatus.ANALYSING);
    }

    /** A queued case has no worker to have lost, so the sweep must not touch it. */
    @Test
    void ignoresCasesThatWereNeverClaimed() throws Exception {
        UUID postId = reportedPost();

        assertThat(processor.requeueStalled(Duration.ZERO)).isZero();
        assertThat(caseFor(postId).getStatus()).isEqualTo(CaseStatus.QUEUED);
    }

    private UUID reportedPost() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);

        mockMvc.perform(post("/api/reports")
                        .header("Authorization", bearer(newUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.ABUSE))))
                .andExpect(status().isCreated());

        return postId;
    }

    private ModerationCase caseFor(UUID postId) {
        return caseRepository
                .findOpenByTarget(TargetType.POST, postId)
                .orElseThrow(() -> new AssertionError("No open case for post " + postId));
    }

    private UUID createPost(User author) throws Exception {
        String response = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(
                                uniqueForumKey(), "Test post", "You are an idiot and everyone knows it"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(JsonPath.read(response, "$.id"));
    }
}
