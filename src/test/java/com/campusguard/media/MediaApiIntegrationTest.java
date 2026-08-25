package com.campusguard.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class MediaApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void normalizesAnImageAndMakesItAvailableThroughAttachedContent() throws Exception {
        User owner = newUser();
        byte[] source = png();
        String upload = mockMvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", "proof.png", "image/png", source))
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andReturn().getResponse().getContentAsString();
        UUID mediaId = UUID.fromString(JsonPath.read(upload, "$.id"));

        mockMvc.perform(get("/api/media/{id}", mediaId))
                .andExpect(status().isNotFound());

        String created = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Image", "", mediaId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaUrl").value("/api/media/" + mediaId))
                .andReturn().getResponse().getContentAsString();
        UUID postId = UUID.fromString(JsonPath.read(created, "$.id"));

        byte[] served = mockMvc.perform(get("/api/media/{id}", mediaId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(served))).isNotNull();

        mockMvc.perform(delete("/api/posts/{id}", postId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/media/{id}", mediaId))
                .andExpect(status().isNotFound());
    }

    @Test
    void preventsOneUserFromAttachingAnotherUsersUpload() throws Exception {
        User owner = newUser();
        User attacker = newUser();
        String upload = mockMvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", "proof.png", "image/png", png()))
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID mediaId = UUID.fromString(JsonPath.read(upload, "$.id"));

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Stolen", "Body", mediaId))))
                .andExpect(status().isNotFound());
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0xff3366);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
