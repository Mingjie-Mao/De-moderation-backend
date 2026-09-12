package com.campusguard.moderation.engine.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One resilience policy per configured model, shared by everything that calls it.
 *
 * <p>Exists so that the circuit is a property of the model rather than of
 * whoever happens to be calling. A verdict and an investigation go to the same
 * endpoint under the same quota; if each built its own breaker, a throttled
 * account would show up as one healthy path and one broken one, and neither
 * would fail fast at the right time.
 *
 * <p>Per model rather than one for everything, for the reason
 * {@code AiEngineConfig} already gives: two models being scored against each
 * other must not be able to open one another's circuit, or a comparison run
 * stops meaning anything the moment either provider wobbles.
 */
public class ModelPolicies {

    private final Map<String, ResiliencePolicy> byModel = new LinkedHashMap<>();
    private final String primary;

    public ModelPolicies(AiProperties properties) {
        List<String> configured = properties.models().stream()
                .map(String::trim)
                .filter(model -> !model.isEmpty())
                .toList();

        configured.forEach(model -> byModel.put(model, new ResiliencePolicy(properties)));
        this.primary = configured.isEmpty() ? null : configured.getFirst();
    }

    public ResiliencePolicy forModel(String model) {
        ResiliencePolicy policy = byModel.get(model);

        if (policy == null) {
            throw new IllegalArgumentException(
                    "No resilience policy for model '%s'. Configured models: %s."
                            .formatted(model, byModel.keySet()));
        }

        return policy;
    }

    /**
     * The model live traffic uses, which is also the one an assistant should use.
     *
     * <p>Not a preference: the point of sharing a circuit is defeated if the
     * assistant is pointed at a model the queue never calls.
     */
    public String primaryModel() {
        if (primary == null) {
            throw new IllegalStateException("No model is configured.");
        }

        return primary;
    }
}
