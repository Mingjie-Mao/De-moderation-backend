package com.campusguard.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class UserProfileIntegrationTest extends AbstractIntegrationTest {

    @Test
    void patchingOneProfileFieldDoesNotEraseTheOther() throws Exception {
        User user = newUser();

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateProfileRequest("First name", "Existing bio"))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("displayName", "Changed name"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Changed name"))
                .andExpect(jsonPath("$.bio").value("Existing bio"));

        mockMvc.perform(get("/api/users/{id}", user.getId())
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Changed name"))
                .andExpect(jsonPath("$.email").doesNotExist());
    }
}
