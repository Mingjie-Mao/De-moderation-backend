package com.campusguard.moderation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the moderation settings unconditionally.
 *
 * <p>Kept out of {@link ModerationWorkerScheduler} because that class disappears
 * when scheduling is switched off, and the batch size and engine name are still
 * needed by anything that drives the queue by hand.
 */
@Configuration
@EnableConfigurationProperties(ModerationProperties.class)
public class ModerationConfig {
}
