package com.campusguard.moderation.engine.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param callTimeout how long one model call may take before it is abandoned.
 *     A moderation queue that is slow is recoverable; one whose threads are all
 *     parked on a call that will never answer is not.
 * @param failureRateThreshold percentage of recent calls that may fail before the
 *     circuit opens
 * @param openDuration how long the circuit stays open before probing again
 * @param slidingWindowSize how many recent calls the failure rate is measured over
 * @param maxAttempts total attempts per case, including the first. Two means one
 *     corrective retry: a model that produced nonsense twice with the mistake
 *     pointed out is not going to produce sense on the third try, and each
 *     attempt is paid for.
 * @param rateLimitRetries how many times to wait and try again when the provider
 *     says we are going too fast. Distinct from {@code maxAttempts}, which is
 *     about the model getting the answer's shape wrong; this is about the
 *     provider declining to answer yet.
 * @param rateLimitBackoff the first wait. Each retry doubles it, with jitter, so
 *     a batch that all hit the limit at once does not march back in step and
 *     collide again.
 */
@ConfigurationProperties(prefix = "campusguard.moderation.ai")
public record AiProperties(
        @DefaultValue("30s") Duration callTimeout,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("30s") Duration openDuration,
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("2") int maxAttempts,
        @DefaultValue("4") int rateLimitRetries,
        @DefaultValue("2s") Duration rateLimitBackoff) {
}
