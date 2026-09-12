package com.campusguard.moderation.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which calls end up behind the same breaker.
 *
 * <p>The sharing itself is pinned by {@code SharedResiliencePolicyTest}; this is
 * the other half, that the right calls are given the right policy. Both matter
 * and neither implies the other: a correct policy handed to the wrong model is
 * as useless as no policy at all.
 */
class ModelPoliciesTest {

    @Test
    void oneModelGetsOnePolicyHoweverOftenItIsAskedFor() {
        ModelPolicies policies = new ModelPolicies(properties("model-a", "model-b"));

        assertThat(policies.forModel("model-a")).isSameAs(policies.forModel("model-a"));
    }

    /**
     * Two models being scored against each other must not be able to open one
     * another's circuit, or a comparison run stops meaning anything the moment
     * either provider wobbles.
     */
    @Test
    void twoModelsGetTwoPolicies() {
        ModelPolicies policies = new ModelPolicies(properties("model-a", "model-b"));

        assertThat(policies.forModel("model-a")).isNotSameAs(policies.forModel("model-b"));
    }

    @Test
    void thePrimaryModelIsTheFirstConfiguredOne() {
        assertThat(new ModelPolicies(properties("model-a", "model-b")).primaryModel()).isEqualTo("model-a");
    }

    @Test
    void ignoresBlankEntriesInTheConfiguredList() {
        ModelPolicies policies = new ModelPolicies(properties("  ", "model-b"));

        assertThat(policies.primaryModel()).isEqualTo("model-b");
    }

    /** Asking for a model nobody configured is a wiring mistake, and says so. */
    @Test
    void refusesAModelItWasNeverToldAbout() {
        ModelPolicies policies = new ModelPolicies(properties("model-a"));

        assertThatThrownBy(() -> policies.forModel("model-z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model-z")
                .hasMessageContaining("model-a");
    }

    private AiProperties properties(String... models) {
        return new AiProperties(
                Duration.ofSeconds(5), 50, Duration.ofSeconds(30), 10, 2, 2, Duration.ofMillis(1), List.of(models));
    }
}
