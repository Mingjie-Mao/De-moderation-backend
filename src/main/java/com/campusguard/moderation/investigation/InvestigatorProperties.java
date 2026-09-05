package com.campusguard.moderation.investigation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled off by default, like the model engine itself. An assistant that
 *     costs several times a verdict per click should be turned on deliberately.
 * @param maxSteps how many times the model may look something up before it must
 *     write. A budget rather than a target: a model still gathering evidence on
 *     the sixth lookup is not converging, and the seventh will not change that.
 * @param maxCorrections how many times a rejected brief may be re-asked.
 *     Separate from {@code maxSteps} for the same reason {@code maxAttempts} is
 *     separate from {@code rateLimitRetries} on the engine: one is about the
 *     model getting the answer's shape wrong, the other about how much looking
 *     up an investigation is worth.
 * @param promptVersion recorded against every call, so briefs stay attributable
 *     to the wording that produced them.
 */
@ConfigurationProperties(prefix = "campusguard.moderation.investigator")
public record InvestigatorProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5") int maxSteps,
        @DefaultValue("1") int maxCorrections,
        @DefaultValue("inv-v1") String promptVersion) {
}
