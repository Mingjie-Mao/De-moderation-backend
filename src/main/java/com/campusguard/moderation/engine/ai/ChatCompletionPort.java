package com.campusguard.moderation.engine.ai;

/**
 * The narrowest useful view of a language model: text in, text out.
 *
 * <p>Everything this project treats as engineering rather than prompting sits
 * around this interface instead of inside the vendor's client. The timeout, the
 * circuit breaker, the corrective retry and the fallback all operate on this
 * type, which means each of them can be tested by handing the engine a stub that
 * hangs, throws or lies, with no network involved and no credentials required.
 *
 * <p>Wrapping the vendor SDK's own retry and timeout knobs instead would put
 * that behaviour somewhere it can only be exercised against the real service.
 */
public interface ChatCompletionPort {

    /** Recorded on every invocation, so cost can be attributed after a model change. */
    String modelName();

    CompletionResult complete(String systemPrompt, String userPrompt);

    /**
     * @param promptTokens null when the provider does not report usage; recorded
     *     as unknown rather than guessed, since a fabricated token count would
     *     quietly corrupt the cost figures this table exists to produce
     */
    record CompletionResult(String text, Integer promptTokens, Integer completionTokens) {
    }
}
