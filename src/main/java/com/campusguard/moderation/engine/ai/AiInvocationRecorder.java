package com.campusguard.moderation.engine.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the record of a model call.
 *
 * <p>Failures are recorded with the same care as successes. The rate at which a
 * provider times out, refuses credentials or returns nonsense is the number that
 * says whether the fallback is load-bearing, and it cannot be recovered later
 * from anything else.
 */
@Service
public class AiInvocationRecorder {

    private final AiInvocationRepository repository;
    private final ObjectMapper objectMapper;

    public AiInvocationRecorder(AiInvocationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(
            UUID caseId,
            String engine,
            String model,
            String promptVersion,
            String contentHash,
            int attempt,
            InvocationStatus status,
            Integer promptTokens,
            Integer completionTokens,
            long latencyMs,
            String rawText,
            String error) {

        // No case means the engine is being measured rather than used, and there
        // is nothing to attribute the call to.
        if (caseId == null) {
            return;
        }

        repository.save(new AiInvocation(
                caseId,
                engine,
                model,
                promptVersion,
                contentHash,
                attempt,
                status,
                promptTokens,
                completionTokens,
                (int) Math.min(latencyMs, Integer.MAX_VALUE),
                asJson(rawText),
                error));
    }

    /**
     * The column is JSONB, and the interesting failures are precisely the ones
     * where the model did not return JSON. Unparseable output is wrapped rather
     * than dropped, because a response that could not be read is the thing you
     * most want to look at afterwards.
     */
    private Map<String, Object> asJson(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawText, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of("unparseable", rawText);
        }
    }
}
