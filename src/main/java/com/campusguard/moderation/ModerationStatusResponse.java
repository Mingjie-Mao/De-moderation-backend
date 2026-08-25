package com.campusguard.moderation;

/**
 * The moderation capability a client will actually receive for newly filed
 * reports.
 *
 * <p>{@code configuredEngine} is retained even when it could not be registered,
 * while {@code activeEngine} is the engine the worker will call. That distinction
 * prevents a missing model credential from being presented as active LLM review.
 */
public record ModerationStatusResponse(
        String configuredEngine,
        String activeEngine,
        String fallbackEngine,
        boolean configuredEngineAvailable,
        boolean llmActive) {
}
