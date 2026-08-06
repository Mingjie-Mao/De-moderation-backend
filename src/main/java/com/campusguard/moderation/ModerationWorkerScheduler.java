package com.campusguard.moderation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The clock behind the worker, kept apart from the work itself.
 *
 * <p>Splitting them is what lets tests exercise the queue by calling
 * {@link ModerationWorker#runOnce()} directly, with this switched off. A test
 * that instead waits for a scheduled poll is a test that either sleeps longer
 * than it needs to or fails intermittently, and usually both.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "campusguard.moderation.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ModerationWorkerScheduler {

    private final ModerationWorker worker;

    public ModerationWorkerScheduler(ModerationWorker worker) {
        this.worker = worker;
    }

    /**
     * Polling rather than an in-process event on report creation, because the
     * queue has to survive a restart: work that only exists as a scheduled task in
     * one JVM's memory is lost when that JVM dies, while a row with status QUEUED
     * is still there afterwards.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate} so a slow batch delays
     * the next poll instead of stacking runs on top of each other.
     */
    @Scheduled(
            fixedDelayString = "${campusguard.moderation.poll-interval:2s}",
            initialDelayString = "${campusguard.moderation.poll-interval:2s}")
    public void poll() {
        worker.runOnce();
    }
}
