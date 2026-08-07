package com.campusguard.evaluation;

import com.campusguard.moderation.ModerationDecision;
import java.util.List;

/**
 * What one engine did with one sample.
 *
 * <p>Every sample is kept, not only the ones an engine got wrong. Aggregate
 * scores say which engine is better on average; they cannot say whether two
 * engines fail on the same content or on different content, and that difference
 * decides whether a change is an improvement or a trade. Answering it needs the
 * per-sample record from both runs.
 *
 * @param actual null when the call itself failed, which is distinct from the
 *     engine answering wrongly and must not be counted as a wrong answer
 * @param error null on success; set when the engine threw for this sample
 */
public record SampleOutcome(
        String sampleId,
        String category,
        ModerationDecision expected,
        ModerationDecision actual,
        double confidence,
        List<String> ruleCodes,
        String rationale,
        String excerpt,
        long latencyMicros,
        Integer promptTokens,
        Integer completionTokens,
        String error) {

    public boolean failed() {
        return error != null;
    }

    /** A failed call is neither correct nor an error of judgement; it is an absence of one. */
    public boolean correct() {
        return !failed() && actual == expected;
    }

    public boolean misjudged() {
        return !failed() && actual != expected;
    }
}
