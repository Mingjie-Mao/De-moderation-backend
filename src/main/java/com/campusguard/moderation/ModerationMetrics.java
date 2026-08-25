package com.campusguard.moderation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class ModerationMetrics implements MeterBinder {
    private final ModerationCaseRepository cases;

    public ModerationMetrics(ModerationCaseRepository cases) {
        this.cases = cases;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (CaseStatus status : CaseStatus.values()) {
            Gauge.builder("campusguard.moderation.cases", cases,
                            repository -> repository.countByStatus(status))
                    .tag("status", status.name())
                    .description("Number of moderation cases by workflow state")
                    .register(registry);
        }
        Gauge.builder("campusguard.moderation.sla.overdue", cases,
                        repository -> repository.countByStatusAndReviewDueAtBefore(
                                CaseStatus.AWAITING_REVIEW, Instant.now()))
                .description("Moderation cases awaiting review past their due time")
                .register(registry);
    }
}
