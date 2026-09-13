package com.campusguard.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The clock behind the sweep, kept apart from the sweep itself — the same split
 * as {@code ModerationWorkerScheduler}, and for the same reason: a test that
 * waits for a scheduled job either sleeps longer than it needs to or fails
 * intermittently, and usually both.
 *
 * <p>Off unless asked for. This job deletes things, and the decision to run
 * something that deletes belongs to whoever read what it deletes, not to a
 * default.
 */
@Configuration
@ConditionalOnProperty(name = "campusguard.media.sweep.enabled", havingValue = "true")
public class MediaSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(MediaSweepScheduler.class);

    private final MediaSweep sweep;

    public MediaSweepScheduler(MediaSweep sweep, MediaProperties properties) {
        this.sweep = sweep;
        log.info(
                "Media orphan sweep is on: every {}, deleting orphans older than {}, at most {} of each kind per pass.",
                properties.sweep().interval(), properties.sweep().grace(), properties.sweep().batchSize());
    }

    /**
     * {@code fixedDelay}, so a pass that takes longer than the interval delays
     * the next one rather than running two sweeps over the same keys at once.
     */
    @Scheduled(
            fixedDelayString = "${campusguard.media.sweep.interval:1h}",
            initialDelayString = "${campusguard.media.sweep.interval:1h}")
    public void sweep() {
        try {
            sweep.runOnce();
        } catch (RuntimeException ex) {
            // A scheduled method that throws is not rescheduled by some
            // executors, which would turn one bad pass into a sweep that never
            // runs again and says nothing about it.
            log.error("Media sweep failed; it will be attempted again next interval.", ex);
        }
    }
}
