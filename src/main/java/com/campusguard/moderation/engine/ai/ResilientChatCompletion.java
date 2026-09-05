package com.campusguard.moderation.engine.ai;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * A model call with a deadline and a memory.
 *
 * <p>Now a thin adapter over {@link ResiliencePolicy}, which holds the behaviour
 * this class used to. The split happened when tool calling arrived: it is a
 * second kind of call to the same endpoint under the same quota, and the two
 * have to share one circuit rather than each keep its own opinion about whether
 * the provider is healthy.
 */
public class ResilientChatCompletion implements ChatCompletionPort {

    private final ChatCompletionPort delegate;
    private final ResiliencePolicy policy;

    /** For a model nothing else calls. Gives this port a circuit of its very own. */
    public ResilientChatCompletion(ChatCompletionPort delegate, AiProperties properties) {
        this(delegate, new ResiliencePolicy(properties));
    }

    /**
     * For a model reached by more than one kind of call. Passing the same policy
     * to each is what keeps them behind one circuit.
     */
    public ResilientChatCompletion(ChatCompletionPort delegate, ResiliencePolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    @Override
    public CompletionResult complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, java.util.List.of());
    }

    @Override
    public CompletionResult complete(
            String systemPrompt,
            String userPrompt,
            java.util.List<com.campusguard.moderation.engine.ModerationRequest.MediaInput> media) {
        return policy.execute(() -> delegate.complete(systemPrompt, userPrompt, media));
    }

    /** Exposed so tests and health reporting can see the breaker without reaching through the field. */
    public CircuitBreaker circuitBreaker() {
        return policy.circuitBreaker();
    }
}
