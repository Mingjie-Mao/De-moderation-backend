package com.campusguard.evaluation;

import com.campusguard.moderation.engine.ai.InvocationStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Captures token usage during an evaluation run.
 *
 * <p>Evaluation samples have no moderation case, so nothing about them is written
 * to {@code ai_invocations} — a row there means real content was moderated, and
 * filling the table with benchmark traffic would corrupt the production cost
 * figures it exists to produce. The usage still has to reach the report, so the
 * recorder announces every call here on its way past.
 *
 * <p>Scoped to the calling thread and opened explicitly around one sample, so a
 * retry's second call is attributed to the sample that caused it and nothing
 * leaks between samples or between engines. Inert unless a run has opened it,
 * which is why leaving it wired in production costs nothing.
 */
@Component
public class EvaluationUsageCollector {

    private final ThreadLocal<List<CallUsage>> open = new ThreadLocal<>();

    public void begin() {
        open.set(new ArrayList<>());
    }

    /**
     * @return every call made since {@link #begin()}, and closes the scope. Always
     *     clears, so an engine that threw cannot leave a half-filled scope behind
     *     for the next sample to inherit.
     */
    public List<CallUsage> end() {
        List<CallUsage> captured = open.get();
        open.remove();
        return captured == null ? List.of() : List.copyOf(captured);
    }

    /** Reads the open scope without closing it, so the caller can close in a finally. */
    public List<CallUsage> current() {
        List<CallUsage> captured = open.get();
        return captured == null ? List.of() : List.copyOf(captured);
    }

    public void observe(
            String engine, InvocationStatus status, Integer promptTokens, Integer completionTokens, long latencyMs) {

        List<CallUsage> captured = open.get();
        if (captured == null) {
            return;
        }
        captured.add(new CallUsage(engine, status, promptTokens, completionTokens, latencyMs));
    }

    public record CallUsage(
            String engine, InvocationStatus status, Integer promptTokens, Integer completionTokens, long latencyMs) {
    }
}
