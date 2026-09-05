package com.campusguard.moderation.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusguard.moderation.engine.ai.AiProperties;
import com.campusguard.moderation.engine.ai.ChatCompletionPort;
import com.campusguard.moderation.engine.ai.InvocationStatus;
import com.campusguard.moderation.engine.ai.ModelCallException;
import com.campusguard.moderation.engine.ai.ResiliencePolicy;
import com.campusguard.moderation.engine.ai.ResilientChatCompletion;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * One circuit for one endpoint, whatever kind of call is made to it.
 *
 * <p>An investigation makes several model calls where a verdict makes one. Left
 * outside the circuit it would become the largest single source of calls to a
 * provider that has stopped answering, spending the full timeout on each while
 * the moderation queue — inside the circuit — was already failing fast. Worse,
 * the two would disagree in the logs about whether the provider was up.
 *
 * <p>Both directions are checked, because sharing is not a property either side
 * can have on its own.
 */
class SharedResiliencePolicyTest {

    @Test
    void anInvestigationIsRefusedAfterModerationHasOpenedTheCircuit() {
        AtomicInteger toolCalls = new AtomicInteger();
        ResiliencePolicy shared = new ResiliencePolicy(properties());

        ResilientChatCompletion chat = new ResilientChatCompletion(brokenChat(), shared);
        ResilientToolCalling tools = new ResilientToolCalling(countingTools(toolCalls), shared);

        openTheCircuitThrough(chat);

        assertThatThrownBy(() -> tools.next("system", List.of(), List.of()))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).status())
                .isEqualTo(InvocationStatus.CIRCUIT_OPEN);

        assertThat(toolCalls.get()).as("the provider was never contacted").isZero();
    }

    @Test
    void moderationIsRefusedAfterAnInvestigationHasOpenedTheCircuit() {
        AtomicInteger chatCalls = new AtomicInteger();
        ResiliencePolicy shared = new ResiliencePolicy(properties());

        ResilientToolCalling tools = new ResilientToolCalling(brokenTools(), shared);
        ResilientChatCompletion chat = new ResilientChatCompletion(countingChat(chatCalls), shared);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> tools.next("system", List.of(), List.of()))
                    .isInstanceOf(ModelCallException.class);
        }

        assertThatThrownBy(() -> chat.complete("s", "u"))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).status())
                .isEqualTo(InvocationStatus.CIRCUIT_OPEN);

        assertThat(chatCalls.get()).isZero();
    }

    /**
     * The other half of the claim: two policies really are two circuits. Without
     * this, the tests above would pass against an implementation that refused
     * everything for some unrelated reason.
     */
    @Test
    void separatePoliciesDoNotAffectEachOther() {
        AtomicInteger toolCalls = new AtomicInteger();

        ResilientChatCompletion chat = new ResilientChatCompletion(brokenChat(), new ResiliencePolicy(properties()));
        ResilientToolCalling tools =
                new ResilientToolCalling(countingTools(toolCalls), new ResiliencePolicy(properties()));

        openTheCircuitThrough(chat);

        assertThat(tools.next("system", List.of(), List.of()).turn())
                .isInstanceOf(ToolCallingPort.Turn.Finished.class);
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    /** A tool-calling turn is subject to the same deadline as a completion. */
    @Test
    void abandonsAToolCallingTurnThatOutlastsItsBudget() {
        ResilientToolCalling tools = new ResilientToolCalling(
                hangingTools(), new ResiliencePolicy(properties(Duration.ofMillis(200))));

        assertThatThrownBy(() -> tools.next("system", List.of(), List.of()))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).status())
                .isEqualTo(InvocationStatus.TIMEOUT);
    }

    /** Two failures fill the window this policy is configured with. */
    private void openTheCircuitThrough(ResilientChatCompletion chat) {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> chat.complete("s", "u")).isInstanceOf(ModelCallException.class);
        }
    }

    private AiProperties properties() {
        return properties(Duration.ofSeconds(5));
    }

    private AiProperties properties(Duration timeout) {
        // A window of two, so the circuit opens on the second failure rather than
        // the tenth; and a 1ms backoff, since the behaviour under test is not the
        // waiting.
        return new AiProperties(
                timeout, 50, Duration.ofSeconds(30), 2, 2, 2, Duration.ofMillis(1), List.of("stub-model"));
    }

    private ChatCompletionPort brokenChat() {
        return new ChatCompletionPort() {
            @Override
            public String modelName() {
                return "stub-model";
            }

            @Override
            public CompletionResult complete(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("upstream is down");
            }
        };
    }

    private ChatCompletionPort countingChat(AtomicInteger calls) {
        return new ChatCompletionPort() {
            @Override
            public String modelName() {
                return "stub-model";
            }

            @Override
            public CompletionResult complete(String systemPrompt, String userPrompt) {
                calls.incrementAndGet();
                return new CompletionResult("{}", 1, 1);
            }
        };
    }

    private ToolCallingPort brokenTools() {
        return tools(() -> {
            throw new IllegalStateException("upstream is down");
        });
    }

    private ToolCallingPort countingTools(AtomicInteger calls) {
        return tools(() -> {
            calls.incrementAndGet();
            return new ToolCallingPort.Response(new ToolCallingPort.Turn.Finished("{}"), 1, 1);
        });
    }

    private ToolCallingPort hangingTools() {
        return tools(() -> {
            Thread.sleep(Duration.ofSeconds(30));
            return new ToolCallingPort.Response(new ToolCallingPort.Turn.Finished("never gets here"), null, null);
        });
    }

    private ToolCallingPort tools(ThrowingSupplier body) {
        return new ToolCallingPort() {
            @Override
            public String modelName() {
                return "stub-model";
            }

            @Override
            public Response next(String system, List<Message> history, List<ToolSpec> specs) {
                try {
                    return body.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                } catch (RuntimeException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        ToolCallingPort.Response get() throws Exception;
    }
}
