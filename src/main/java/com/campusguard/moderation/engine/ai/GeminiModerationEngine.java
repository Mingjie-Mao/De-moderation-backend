package com.campusguard.moderation.engine.ai;

import com.campusguard.moderation.engine.ModerationEngine;
import com.campusguard.moderation.engine.ModerationRequest;
import com.campusguard.moderation.engine.ModerationVerdict;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A moderation engine backed by a language model.
 *
 * <p>Implements the same interface as term matching, which is what lets the
 * worker, the fallback and the evaluation harness treat the two identically.
 *
 * <p>Retries are for the model getting the shape wrong, not for the network
 * getting in the way. A response that failed validation is worth asking again
 * with the specific complaint attached, because models correct that reliably. A
 * timeout or a refused credential is not worth asking again immediately: the
 * second call takes the same budget to fail the same way, and the circuit
 * breaker exists precisely so that repeated failure stops costing time at all.
 */
public class GeminiModerationEngine implements ModerationEngine {

    private static final Logger log = LoggerFactory.getLogger(GeminiModerationEngine.class);

    private final ChatCompletionPort completions;
    private final ModerationPrompt prompt;
    private final VerdictParser parser;
    private final AiInvocationRecorder recorder;
    private final AiProperties properties;

    public GeminiModerationEngine(
            ChatCompletionPort completions,
            ModerationPrompt prompt,
            VerdictParser parser,
            AiInvocationRecorder recorder,
            AiProperties properties) {
        this.completions = completions;
        this.prompt = prompt;
        this.parser = parser;
        this.recorder = recorder;
        this.properties = properties;
    }

    /**
     * The model and the prompt version together are the identity.
     *
     * <p>Both change what the engine answers, so both belong in the name. Two
     * models are then two engines the harness scores side by side, and a later
     * prompt is a third — rather than any of them silently changing the meaning of
     * numbers already published under one label.
     */
    @Override
    public String name() {
        return completions.modelName() + "/" + prompt.version();
    }

    @Override
    public boolean isLanguageModel() {
        return true;
    }

    @Override
    public ModerationVerdict evaluate(ModerationRequest request) {
        String system = prompt.system();
        String contentHash = sha256(request.fullText());
        String correction = null;

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            String user = correction == null
                    ? prompt.user(request)
                    : prompt.user(request) + "\n\nYour previous answer was rejected: " + correction
                            + "\nAnswer again as a single JSON object that fixes this.";

            long startedAt = System.nanoTime();
            ChatCompletionPort.CompletionResult result;

            try {
                result = completions.complete(system, user);
            } catch (ModelCallException ex) {
                recorder.record(
                        request.caseId(), name(), completions.modelName(), prompt.version(),
                        contentHash, attempt, ex.status(), null, null, elapsedMillis(startedAt),
                        null, ex.getMessage());
                throw ex;
            }

            long latency = elapsedMillis(startedAt);

            try {
                ModerationVerdict verdict = parser.parse(result.text());
                recorder.record(
                        request.caseId(), name(), completions.modelName(), prompt.version(),
                        contentHash, attempt, InvocationStatus.SUCCESS,
                        result.promptTokens(), result.completionTokens(), latency,
                        result.text(), null);
                return verdict;

            } catch (InvalidVerdictException ex) {
                recorder.record(
                        request.caseId(), name(), completions.modelName(), prompt.version(),
                        contentHash, attempt, InvocationStatus.INVALID_RESPONSE,
                        result.promptTokens(), result.completionTokens(), latency,
                        result.text(), ex.getMessage());

                log.warn("Model answer rejected on attempt {} of {}: {}",
                        attempt, properties.maxAttempts(), ex.getMessage());
                correction = ex.getMessage();
            }
        }

        throw new ModelCallException(
                InvocationStatus.INVALID_RESPONSE,
                "The model did not produce a usable verdict in " + properties.maxAttempts() + " attempts.");
    }

    private long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every JVM.", ex);
        }
    }
}
