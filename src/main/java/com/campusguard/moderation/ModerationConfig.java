package com.campusguard.moderation;

import com.campusguard.moderation.engine.ai.AiProperties;
import com.campusguard.moderation.investigation.InvestigatorProperties;
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
@EnableConfigurationProperties({
    ModerationProperties.class,
    AiProperties.class,
    // Registered even though nothing injects it yet. The alternative is a
    // settings block that silently does nothing until some later commit
    // remembers to wire it, which is how a step limit ends up not applying.
    InvestigatorProperties.class,
    com.campusguard.evaluation.EvaluationProperties.class
})
public class ModerationConfig {
}
