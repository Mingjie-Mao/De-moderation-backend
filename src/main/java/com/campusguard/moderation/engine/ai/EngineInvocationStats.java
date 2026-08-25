package com.campusguard.moderation.engine.ai;

/**
 * Aggregate of what an engine has actually done in production.
 *
 * <p>Separate from benchmark scores on purpose. A benchmark says how well an
 * engine judges a set of samples someone chose; this says how often it answered
 * at all, how long it took on real traffic, and what that came to in tokens. An
 * engine can score well on the first and still be unusable on the second.
 */
public interface EngineInvocationStats {

    String getEngine();

    long getCalls();

    long getSuccesses();

    long getFailures();

    double getAvgLatencyMs();

    double getP95LatencyMs();

    long getPromptTokens();

    long getCompletionTokens();
}
