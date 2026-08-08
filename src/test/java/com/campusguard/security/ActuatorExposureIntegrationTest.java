package com.campusguard.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * What an unauthenticated caller can learn about the host.
 *
 * <p>Actuator's defaults are generous in a way that is easy to ship without
 * noticing: {@code show-details: always} answers an anonymous GET with the
 * deployment's absolute filesystem path, its disk capacity and the database
 * engine, and the rest of the endpoints fall through to "any authenticated
 * user", which on a public forum means any member who registered a minute ago.
 *
 * <p>Neither is visible from reading a controller, because no controller is
 * involved. This is the only place the boundary is written down, so it is the
 * only place it can be pinned.
 *
 * <p>Deliberately no {@code @TestPropertySource}. Pinning the settings here
 * would make the test assert its own configuration and pass happily while the
 * shipped {@code application.yml} said something else — which is the entire
 * failure it exists to catch.
 */
class ActuatorExposureIntegrationTest extends AbstractIntegrationTest {

    /**
     * Kept open on purpose: an orchestrator has no credential and still has to be
     * able to decide the instance is alive.
     */
    @Test
    void healthAnswersWithoutACredential() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void healthTellsAnAnonymousCallerNothingAboutTheHost() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist())
                // The specific leak that prompted this: the absolute path the
                // application is deployed from, username and all.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("path"))));
    }

    @Test
    void operationalEndpointsAreClosedToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }

    /**
     * The half that the framework's default got wrong. Signing up is open to
     * anyone, so "authenticated" is not a meaningful bar for JVM internals and
     * HTTP timings.
     */
    @Test
    void operationalEndpointsAreClosedToOrdinaryMembers() throws Exception {
        mockMvc.perform(get("/actuator/metrics").header("Authorization", bearer(newUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorsStillSeeEverything() throws Exception {
        String token = bearer(newAdmin());

        mockMvc.perform(get("/actuator/metrics").header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }
}
