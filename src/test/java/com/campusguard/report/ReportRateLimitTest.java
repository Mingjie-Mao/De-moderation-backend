package com.campusguard.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * One account cannot open unlimited moderation cases.
 *
 * <p>Nothing else in the workflow bounds this. Reporting the same thing twice is
 * already refused, but reporting a hundred different things is a hundred
 * individually legitimate requests, a hundred billable engine calls and a hundred
 * items in a human queue.
 */
@TestPropertySource(properties = {
    "campusguard.reports.per-user-limit=3",
    "campusguard.reports.per-user-window=1h"
})
class ReportRateLimitTest extends AbstractIntegrationTest {

    @Autowired
    private ModerationCaseRepository caseRepository;

    @Test
    void refusesFurtherReportsOnceAnAccountHitsItsLimit() throws Exception {
        User reporter = newUser();
        List<UUID> posts = createPosts(4);

        for (int i = 0; i < 3; i++) {
            report(reporter, posts.get(i)).andExpect(status().isCreated());
        }

        report(reporter, posts.get(3))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("Rate limit exceeded"));

        // The refusal happens before a case is opened, so a throttled reporter
        // costs nothing downstream.
        assertThat(caseRepository.findOpenByTarget(TargetType.POST, posts.get(3))).isEmpty();
    }

    /** The limit is per account, not global: one noisy reporter must not mute everyone else. */
    @Test
    void doesNotPenaliseOtherAccounts() throws Exception {
        List<UUID> posts = createPosts(4);
        User noisy = newUser();

        for (int i = 0; i < 3; i++) {
            report(noisy, posts.get(i)).andExpect(status().isCreated());
        }
        report(noisy, posts.get(3)).andExpect(status().isTooManyRequests());

        report(newUser(), posts.get(3)).andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions report(User reporter, UUID postId) throws Exception {
        return mockMvc.perform(post("/api/reports")
                .header("Authorization", bearer(reporter))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.SPAM))));
    }

    private List<UUID> createPosts(int count) throws Exception {
        User author = newUser();
        String forumKey = uniqueForumKey();
        List<UUID> ids = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String response = mockMvc.perform(post("/api/posts")
                            .header("Authorization", bearer(author))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CreatePostRequest(forumKey, "Post " + i, "Ordinary body."))))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            ids.add(UUID.fromString(JsonPath.read(response, "$.id")));
        }

        return ids;
    }
}
