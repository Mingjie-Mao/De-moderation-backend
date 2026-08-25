package com.campusguard.evaluation;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Every engine's answer to every sample, one row each.
 *
 * <p>The markdown report truncates its tables so it stays readable; this file does
 * not truncate anything. Error analysis on two hundred samples happens in a
 * spreadsheet with a filter on it, not by scrolling a document.
 */
@Component
public class EvaluationCsvWriter {

    private static final String HEADER =
            "engine,sample_id,category,expected,actual,outcome,confidence,rule_codes,"
                    + "latency_us,prompt_tokens,completion_tokens,error,excerpt,rationale";

    public String render(BenchmarkReport report) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');

        for (EvaluationResult result : report.results()) {
            for (SampleOutcome outcome : result.outcomes()) {
                out.append(row(result.engineName(), outcome)).append('\n');
            }
        }

        return out.toString();
    }

    private String row(String engineName, SampleOutcome outcome) {
        return String.join(
                ",",
                quote(engineName),
                quote(outcome.sampleId()),
                quote(outcome.category()),
                quote(name(outcome.expected())),
                quote(name(outcome.actual())),
                // Three states, not two: a call that failed is neither right nor
                // wrong, and collapsing it into "wrong" would blame the model for
                // an outage.
                quote(outcome.failed() ? "ERROR" : outcome.correct() ? "CORRECT" : "WRONG"),
                outcome.failed() ? "" : String.valueOf(outcome.confidence()),
                quote(String.join(" ", outcome.ruleCodes())),
                String.valueOf(outcome.latencyMicros()),
                outcome.promptTokens() == null ? "" : String.valueOf(outcome.promptTokens()),
                outcome.completionTokens() == null ? "" : String.valueOf(outcome.completionTokens()),
                quote(outcome.error()),
                quote(outcome.excerpt()),
                quote(outcome.rationale()));
    }

    private String name(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    /**
     * Always quoted and always doubling embedded quotes. Content under evaluation
     * is arbitrary user text, so commas, quotes and newlines in it are the normal
     * case rather than an edge one.
     */
    private String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return '"' + value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + '"';
    }

    /** Exposed for tests that want to check a header contract without a full run. */
    public List<String> columns() {
        return List.of(HEADER.split(","));
    }
}
