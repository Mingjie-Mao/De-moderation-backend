package com.campusguard.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

/**
 * The sweep deletes things, so what it will not delete matters more than what it
 * will.
 *
 * <p>Assertions here are about named rows and named keys rather than about
 * counts. Integration tests share one database, so a count of deletions is a
 * count of this test's orphans plus whatever every other test left lying around,
 * and an assertion on that number would fail for reasons that have nothing to do
 * with the sweep.
 */
@TestPropertySource(properties = {
        // The grace period is what protects an upload in flight. Zero here
        // because every orphan this test makes is seconds old, and a 24-hour wait
        // cannot be asserted on.
        "campusguard.media.sweep.grace=0s",
        "campusguard.media.sweep.batch-size=500",
        // The scheduler stays off. The sweep is driven by calling it, so the test
        // neither sleeps nor races a background pass.
        "campusguard.media.sweep.enabled=false"
})
class MediaSweepIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MediaSweep sweep;

    @Autowired
    MediaStorage storage;

    @Autowired
    MediaObjectRepository mediaObjects;

    /** Uploaded, never attached to anything: bytes nobody can reach. */
    @Test
    void deletesMediaThatWasUploadedAndNeverAttached() throws Exception {
        User owner = newUser();
        UUID mediaId = upload(owner);
        String key = mediaObjects.findById(mediaId).orElseThrow().getStorageKey();

        sweep.runOnce();

        assertThat(mediaObjects.findById(mediaId)).isEmpty();
        assertThat(storage.get(key)).isEmpty();
    }

    @Test
    void leavesMediaAttachedToALivePostAlone() throws Exception {
        User owner = newUser();
        UUID mediaId = upload(owner);
        attachToPost(owner, mediaId);
        String key = mediaObjects.findById(mediaId).orElseThrow().getStorageKey();

        sweep.runOnce();

        assertThat(mediaObjects.findById(mediaId)).isPresent();
        assertThat(storage.get(key)).isPresent();
    }

    /**
     * The assertion this whole query was shaped around. A removed post is
     * soft-deleted, and a moderator's decision can be reversed — re-decision and
     * appeal are both features. Sweeping the image of a hidden post would make
     * reinstating it produce a post with a hole in it, and the reinstatement
     * would look like the broken thing.
     */
    @Test
    void leavesMediaAttachedToARemovedPostAlone() throws Exception {
        User owner = newUser();
        UUID mediaId = upload(owner);
        UUID postId = attachToPost(owner, mediaId);

        mockMvc.perform(delete("/api/posts/{id}", postId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        String key = mediaObjects.findById(mediaId).orElseThrow().getStorageKey();

        sweep.runOnce();

        assertThat(mediaObjects.findById(mediaId)).isPresent();
        assertThat(storage.get(key)).isPresent();
    }

    /**
     * An object with no row is unreachable: the only path to a stored key is
     * through the database. It is also invisible, so nothing but this sweep will
     * ever reclaim it.
     */
    @Test
    void deletesAStoredObjectNoRowKnowsAbout() {
        String stray = UUID.randomUUID() + ".png";
        storage.put(stray, "orphaned bytes".getBytes(StandardCharsets.UTF_8), "image/png");

        sweep.runOnce();

        assertThat(storage.get(stray)).isEmpty();
    }

    /**
     * The opposite leak, and the one that must never be deleted here: the row may
     * still be referenced by a live post, and removing it would turn a missing
     * picture into a broken page. It is counted instead, because a rising count is
     * the signature of storage that does not survive deployment.
     */
    @Test
    void countsButDoesNotDeleteARowWhoseBytesAreGone() throws Exception {
        User owner = newUser();
        UUID mediaId = upload(owner);
        attachToPost(owner, mediaId);
        String key = mediaObjects.findById(mediaId).orElseThrow().getStorageKey();
        storage.delete(key);

        MediaSweep.Result result = sweep.runOnce();

        assertThat(result.rowsMissingBytes()).isGreaterThanOrEqualTo(1);
        assertThat(mediaObjects.findById(mediaId)).isPresent();
    }

    /** Nothing to do is a normal outcome and has to be distinguishable from a pass that broke. */
    @Test
    void reportsAnEmptyResultWhenThereIsNothingToClean() {
        sweep.runOnce();

        MediaSweep.Result second = sweep.runOnce();

        assertThat(second.rowsDeleted()).isZero();
        assertThat(second.objectsDeleted()).isZero();
    }

    private UUID upload(User owner) throws Exception {
        String response = mockMvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", "proof.png", "image/png", png()))
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private UUID attachToPost(User owner, UUID mediaId) throws Exception {
        String created = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Image", "", mediaId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.id"));
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
