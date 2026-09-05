package com.campusguard.moderation.investigation;

import com.campusguard.moderation.engine.ai.ResiliencePolicy;
import java.util.List;

/**
 * A tool-calling turn under the same deadline and the same circuit as an
 * ordinary completion.
 *
 * <p>The reason this exists rather than the investigator calling the provider
 * adapter directly. An investigation makes several model calls where a verdict
 * makes one, so an assistant outside the circuit would be the single largest
 * source of calls to a provider that has stopped answering — and it would be
 * spending the full timeout on each of them while the moderation queue, which is
 * inside the circuit, was already degrading in milliseconds.
 *
 * <p>The policy is passed in rather than built here. Sharing the instance with
 * the chat port is the whole point: they are one endpoint under one quota, and a
 * second circuit would report a throttled account as one healthy path and one
 * broken one.
 */
public class ResilientToolCalling implements ToolCallingPort {

    private final ToolCallingPort delegate;
    private final ResiliencePolicy policy;

    public ResilientToolCalling(ToolCallingPort delegate, ResiliencePolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    @Override
    public Response next(String system, List<Message> history, List<ToolSpec> tools) {
        return policy.execute(() -> delegate.next(system, history, tools));
    }
}
