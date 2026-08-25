package com.campusguard.moderation.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.campusguard.common.TargetType;
import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.engine.ModerationRequest;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.moderation.rule.ModerationRule;
import com.campusguard.moderation.rule.RuleProvider;
import com.campusguard.moderation.rule.RuleSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * How the engine behaves when the model misbehaves.
 *
 * <p>Driven through a stub port, so a rejected credential, a malformed answer and
 * a correction cycle are all reproducible without an API key, without a network
 * and without waiting on a real provider to misbehave on cue.
 */
class GeminiModerationEngineTest {

    private final AiInvocationRecorder recorder = mock(AiInvocationRecorder.class);

    /**
     * The retry exists for this: a model that got the shape wrong will usually fix
     * it when told exactly what was wrong, and the complaint is fed back verbatim.
     */
    @Test
    void retriesOnceWithTheValidationComplaintAttached() {
        List<String> prompts = new ArrayList<>();
        AtomicInteger call = new AtomicInteger();

        ChatCompletionPort port = stub(prompts, () -> call.getAndIncrement() == 0
                ? """
                  {"decision":"REMOVE","confidence":1.7,"rationale":"Bad.","ruleCodes":["ABUSE"]}
                  """
                : """
                  {"decision":"REMOVE","confidence":0.85,"rationale":"Personal attack.","ruleCodes":["ABUSE"]}
                  """);

        ModerationVerdict verdict = engine(port).evaluate(request());

        assertThat(verdict.decision()).isEqualTo(ModerationDecision.REMOVE);
        assertThat(verdict.confidence()).isEqualTo(0.85);
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).contains("between 0 and 1");
        assertThat(prompts.get(1)).contains("previous answer was rejected");
    }

    /**
     * A model that produced nonsense twice with the mistake spelled out will not
     * produce sense on the third try, and every attempt is billed. Giving up here
     * hands the case to the fallback rather than looping.
     */
    @Test
    void givesUpAfterTheRetryAlsoFails() {
        ChatCompletionPort port = stub(new ArrayList<>(), () -> "not json at all");

        assertThatThrownBy(() -> engine(port).evaluate(request()))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).status())
                .isEqualTo(InvocationStatus.INVALID_RESPONSE);
    }

    /**
     * A refused credential is not retried. The second call takes the same budget
     * to fail the same way; the circuit breaker is what handles repetition.
     */
    @Test
    void doesNotRetryARefusedCredential() {
        AtomicInteger calls = new AtomicInteger();
        ChatCompletionPort port = new ChatCompletionPort() {
            @Override
            public String modelName() {
                return "stub-model";
            }

            @Override
            public CompletionResult complete(String systemPrompt, String userPrompt) {
                calls.incrementAndGet();
                throw new ModelCallException(InvocationStatus.ERROR, "API key not valid.");
            }
        };

        assertThatThrownBy(() -> engine(port).evaluate(request()))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).status())
                .isEqualTo(InvocationStatus.ERROR);

        assertThat(calls.get()).isEqualTo(1);
    }

    /** Every attempt is recorded, failures included, or the failure rate is unknowable. */
    @Test
    void recordsBothTheRejectedAttemptAndTheAcceptedOne() {
        AtomicInteger call = new AtomicInteger();
        ChatCompletionPort port = stub(new ArrayList<>(), () -> call.getAndIncrement() == 0
                ? "garbage"
                : """
                  {"decision":"ALLOW","confidence":0.6,"rationale":"Fine.","ruleCodes":[]}
                  """);

        UUID caseId = UUID.randomUUID();
        engine(port).evaluate(request().forCase(caseId));

        verify(recorder).record(eq(caseId), any(), any(), any(), any(), eq(1),
                eq(InvocationStatus.INVALID_RESPONSE), any(), any(), anyLong(), any(), any());
        verify(recorder).record(eq(caseId), any(), any(), any(), any(), eq(2),
                eq(InvocationStatus.SUCCESS), any(), any(), anyLong(), any(), isNull());
    }

    @Test
    void namesItselfAfterTheModelAndThePromptSoEachPairIsASeparateEngine() {
        ChatCompletionPort port = stub(new ArrayList<>(), () -> "{}");

        assertThat(engine(port).name()).isEqualTo("stub-model/" + ModerationPromptV1.VERSION);
    }

    private GeminiModerationEngine engine(ChatCompletionPort port) {
        RuleProvider rules = () -> List.of(
                new ModerationRule("ABUSE", "Abuse", "Personal attacks.", RuleSeverity.HIGH, List.of("idiot")));

        return new GeminiModerationEngine(
                port,
                new ModerationPromptV1(rules),
                new VerdictParser(new ObjectMapper(), rules),
                recorder,
                new AiProperties(Duration.ofSeconds(10), 50, Duration.ofSeconds(30), 10, 2, 0, Duration.ofMillis(1), List.of("stub-model")));
    }

    private ModerationRequest request() {
        return sampleRequest();
    }

    /** Shared with {@link ModerationPromptV3Test}, which compares two prompts on one input. */
    static ModerationRequest sampleRequest() {
        return ModerationRequest.of(
                TargetType.POST, UUID.randomUUID(), "A title", "You are an idiot", UUID.randomUUID());
    }

    private ChatCompletionPort stub(List<String> capturedPrompts, java.util.function.Supplier<String> body) {
        return new ChatCompletionPort() {
            @Override
            public String modelName() {
                return "stub-model";
            }

            @Override
            public CompletionResult complete(String systemPrompt, String userPrompt) {
                capturedPrompts.add(userPrompt);
                return new CompletionResult(body.get(), 100, 20);
            }
        };
    }
}
