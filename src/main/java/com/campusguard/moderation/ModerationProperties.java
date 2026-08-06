package com.campusguard.moderation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param engine which registered engine judges cases; the name a
 *     {@link com.campusguard.moderation.engine.ModerationEngine} reports, so
 *     swapping implementations is configuration rather than a code change
 * @param batchSize how many cases one poll claims
 * @param pollInterval how long the worker waits between polls
 * @param stalledAfter how long a case may sit claimed before it is assumed the
 *     worker holding it died and it is returned to the queue
 */
@ConfigurationProperties(prefix = "campusguard.moderation")
public record ModerationProperties(
        @DefaultValue("keyword-v1") String engine,
        /*
         * What the queue degrades to when the primary engine fails. Must never be
         * a model itself, or an outage takes both paths down together.
         */
        @DefaultValue("keyword-v1") String fallbackEngine,
        @DefaultValue("20") int batchSize,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("5m") Duration stalledAfter) {
}
