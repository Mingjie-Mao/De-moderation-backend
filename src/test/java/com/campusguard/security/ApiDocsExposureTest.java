package com.campusguard.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Whether the API surface is enumerable by a stranger.
 *
 * <p>Both answers are defensible and the project needs both, which is why it is a
 * property. Open, Swagger UI works as the console it is documented to be — the
 * browser fetches the spec with no Authorization header, so a closed document
 * leaves a blank page nobody can sign in from. Closed, a deployment stops handing
 * out a map of every route to anyone who asks.
 *
 * <p>Property tests here rather than a comment, because the only thing worse than
 * picking the wrong default is picking the right one and having it silently stop
 * applying.
 */
class ApiDocsExposureTest {

    @Nested
    @TestPropertySource(properties = "campusguard.security.expose-api-docs=true")
    class WhenExposed extends AbstractIntegrationTest {

        @Test
        void anyoneCanReadTheSpecification() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        }
    }

    @Nested
    @TestPropertySource(properties = "campusguard.security.expose-api-docs=false")
    class WhenClosed extends AbstractIntegrationTest {

        @Test
        void aStrangerCannotEnumerateTheRoutes() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        }

        @Test
        void anOrdinaryMemberCannotEither() throws Exception {
            mockMvc.perform(get("/v3/api-docs").header("Authorization", bearer(newUser())))
                    .andExpect(status().isForbidden());
        }

        /** Closed to the public is not closed to the people who run the thing. */
        @Test
        void administratorsStillHaveTheConsole() throws Exception {
            mockMvc.perform(get("/v3/api-docs").header("Authorization", bearer(newAdmin())))
                    .andExpect(status().isOk());
        }
    }
}
