package com.campusguard.moderation.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The failure modes that matter are the slow ones.
 *
 * <p>A rejected API key is loud and instant; anyone would notice it. A provider
 * that accepts the connection and then never answers is what fills a thread pool
 * and stops a queue, and it is the case that never gets tested because testing
 * it against the real service means waiting.
 *
 * <p>Nothing here touches a network or needs a credential: the policy is an
 * ordinary object wrapped around a stub that can be told to hang or throw.
 */
class ResilientChatCompletionTest {

    @Test
    void abandonsACallThatOutlastsItsBudget() {
        ChatCompletionPort hangs = stub(() -> {
            Thread.sleep(Duration.ofSeconds(30));
            return new ChatCompletionPort.CompletionResult("never gets here", null, null);
        });

        ResilientChatCompletion resilient = new ResilientChatCompletion(hangs, properties(Duration.ofMillis(200), 2));

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> resilient.complete("system", "user"))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).status())
                .isEqualTo(InvocationStatus.TIMEOUT);

        // The point of the timeout is that the caller gets control back. Asserting
        // it returned promptly is asserting the feature, not the implementation.
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
    }

    /**
     * A timeout alone still spends the whole budget on every request while a
     * provider is down. The circuit is what turns a ten-second failure into an
     * instant one, so the queue degrades at full speed instead of crawling.
     */
    @Test
    void stopsCallingAProviderThatKeepsFailing() {
        AtomicInteger calls = new AtomicInteger();
        ChatCompletionPort broken = stub(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("upstream is down");
        });

        ResilientChatCompletion resilient = new ResilientChatCompletion(broken, properties(Duration.ofSeconds(5), 2));

        assertThatThrownBy(() -> resilient.complete("s", "u")).isInstanceOf(ModelCallException.class);
        assertThatThrownBy(() -> resilient.complete("s", "u")).isInstanceOf(ModelCallException.class);

        int callsBeforeCircuitOpened = calls.get();

        assertThatThrownBy(() -> resilient.complete("s", "u"))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).status())
                .isEqualTo(InvocationStatus.CIRCUIT_OPEN);

        // Refused locally: the provider was never contacted for the third call.
        assertThat(calls.get()).isEqualTo(callsBeforeCircuitOpened);
    }

    @Test
    void passesASuccessfulCallThrough() {
        ChatCompletionPort works =
                stub(() -> new ChatCompletionPort.CompletionResult("{\"ok\":true}", 12, 34));

        ResilientChatCompletion resilient = new ResilientChatCompletion(works, properties(Duration.ofSeconds(5), 10));

        ChatCompletionPort.CompletionResult result = resilient.complete("s", "u");

        assertThat(result.text()).isEqualTo("{\"ok\":true}");
        assertThat(result.promptTokens()).isEqualTo(12);
    }

    /**
     * Being throttled is the one failure that waiting fixes, so it is the one
     * failure that gets retried.
     */
    @Test
    void waitsAndTriesAgainWhenThrottled() {
        AtomicInteger calls = new AtomicInteger();
        ChatCompletionPort throttlesTwice = stub(() -> {
            if (calls.incrementAndGet() <= 2) {
                throw new ModelCallException(InvocationStatus.RATE_LIMITED, "429 quota exceeded");
            }
            return new ChatCompletionPort.CompletionResult("{\"ok\":true}", 1, 1);
        });

        ResilientChatCompletion resilient =
                new ResilientChatCompletion(throttlesTwice, properties(Duration.ofSeconds(5), 10));

        assertThat(resilient.complete("s", "u").text()).isEqualTo("{\"ok\":true}");
        assertThat(calls.get()).isEqualTo(3);
    }

    /**
     * A refused credential will still be refused two seconds later. Retrying it
     * spends the budget twice to learn the same thing.
     */
    @Test
    void doesNotRetryAFailureThatWaitingCannotFix() {
        AtomicInteger calls = new AtomicInteger();
        ChatCompletionPort refuses = stub(() -> {
            calls.incrementAndGet();
            throw new ModelCallException(InvocationStatus.ERROR, "API key not valid");
        });

        ResilientChatCompletion resilient =
                new ResilientChatCompletion(refuses, properties(Duration.ofSeconds(5), 10));

        assertThatThrownBy(() -> resilient.complete("s", "u"))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).status())
                .isEqualTo(InvocationStatus.ERROR);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void givesUpAsThrottledRatherThanAsBrokenWhenTheLimitPersists() {
        AtomicInteger calls = new AtomicInteger();
        ChatCompletionPort alwaysThrottles = stub(() -> {
            calls.incrementAndGet();
            throw new ModelCallException(InvocationStatus.RATE_LIMITED, "429 quota exceeded");
        });

        ResilientChatCompletion resilient =
                new ResilientChatCompletion(alwaysThrottles, properties(Duration.ofSeconds(5), 10));

        assertThatThrownBy(() -> resilient.complete("s", "u"))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).status())
                .isEqualTo(InvocationStatus.RATE_LIMITED);

        // The first call plus the configured retries.
        assertThat(calls.get()).isEqualTo(3);
    }

    private AiProperties properties(Duration timeout, int window) {
        // Two retries with a 1ms base: the behaviour under test is the retrying,
        // not the waiting, and a real backoff would make the suite sleep.
        return new AiProperties(timeout, 50, Duration.ofSeconds(30), window, 2, 2, Duration.ofMillis(1));
    }

    private ChatCompletionPort stub(ThrowingSupplier body) {
        return new ChatCompletionPort() {
            @Override
            public String modelName() {
                return "stub-model";
            }

            @Override
            public CompletionResult complete(String systemPrompt, String userPrompt) {
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
        ChatCompletionPort.CompletionResult get() throws Exception;
    }
}
