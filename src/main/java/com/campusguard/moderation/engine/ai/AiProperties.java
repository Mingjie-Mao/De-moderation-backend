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
 */
@ConfigurationProperties(prefix = "campusguard.moderation.ai")
public record AiProperties(
        @DefaultValue("10s") Duration callTimeout,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("30s") Duration openDuration,
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("2") int maxAttempts) {
}
