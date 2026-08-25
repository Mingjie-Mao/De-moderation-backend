package com.campusguard.moderation;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drains the moderation queue.
 *
 * <p>Holds no transaction of its own and calls {@link ModerationCaseProcessor}
 * for each step, so that a claim commits before analysis begins and one failing
 * case cannot roll back the batch around it.
 *
 * <p>{@link #runOnce()} is public and does the whole job, which is what lets a
 * test drive the queue to completion deterministically instead of sleeping and
 * hoping a scheduler fired.
 */
@Component
public class ModerationWorker {

    private static final Logger log = LoggerFactory.getLogger(ModerationWorker.class);

    private final ModerationCaseProcessor processor;
    private final ModerationProperties properties;

    public ModerationWorker(ModerationCaseProcessor processor, ModerationProperties properties) {
        this.processor = processor;
        this.properties = properties;
    }

    /**
     * @return how many cases were analysed
     */
    public int runOnce() {
        processor.requeueStalled(properties.stalledAfter());

        List<UUID> claimed = processor.claimBatch(properties.batchSize());

        for (UUID caseId : claimed) {
            try {
                processor.analyse(caseId);
            } catch (RuntimeException ex) {
                // Analysis already turns an engine failure into an escalation, so
                // reaching here means something outside the engine broke. The case
                // stays in ANALYSING and the stalled sweep will offer it again.
                log.error("Case {} could not be analysed; leaving it for the stalled sweep.", caseId, ex);
            }
        }

        return claimed.size();
    }
}
