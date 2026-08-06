package com.campusguard.moderation.engine.ai;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A model call with a deadline and a memory.
 *
 * <p>Composed programmatically rather than through annotations. Resilience4j's
 * annotations need an AOP proxy, which means the behaviour only exists when
 * Spring wires the bean and cannot be exercised by constructing the class in a
 * test. Here the policy is an ordinary object, so a test can open the circuit or
 * trip the timeout directly.
 *
 * <p>The circuit is the part that matters most under a real outage. A timeout
 * alone still spends the full budget on every request while the provider is
 * down; once enough recent calls have failed, the circuit rejects immediately
 * and the queue degrades to the rule engine at full speed instead of crawling.
 */
public class ResilientChatCompletion implements ChatCompletionPort {

    private static final Logger log = LoggerFactory.getLogger(ResilientChatCompletion.class);

    private final ChatCompletionPort delegate;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor;

    public ResilientChatCompletion(ChatCompletionPort delegate, AiProperties properties) {
        this.delegate = delegate;

        this.circuitBreaker = CircuitBreaker.of(
                "moderation-model",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(properties.failureRateThreshold())
                        .waitDurationInOpenState(properties.openDuration())
                        .slidingWindowSize(properties.slidingWindowSize())
                        .minimumNumberOfCalls(properties.slidingWindowSize())
                        .build());

        this.timeLimiter = TimeLimiter.of(
                "moderation-model",
                TimeLimiterConfig.custom()
                        .timeoutDuration(properties.callTimeout())
                        // The abandoned call is interrupted rather than left to
                        // finish unobserved, so a provider that has stopped
                        // answering cannot accumulate parked threads.
                        .cancelRunningFuture(true)
                        .build());

        // Virtual threads: these tasks are almost entirely blocked on network I/O,
        // which is the case they exist for. A fixed pool would cap concurrent
        // moderation at its size for no reason.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();

        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Moderation model circuit {} -> {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    @Override
    public CompletionResult complete(String systemPrompt, String userPrompt) {
        Callable<CompletionResult> timed = TimeLimiter.decorateFutureSupplier(
                timeLimiter, () -> executor.submit(() -> delegate.complete(systemPrompt, userPrompt)));

        try {
            return circuitBreaker.executeCallable(timed);
        } catch (CallNotPermittedException ex) {
            throw new ModelCallException(
                    InvocationStatus.CIRCUIT_OPEN,
                    "Recent model calls have been failing; this one was refused without being sent.",
                    ex);
        } catch (TimeoutException ex) {
            throw new ModelCallException(
                    InvocationStatus.TIMEOUT, "The model did not answer within the budget.", ex);
        } catch (ModelCallException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ModelCallException(InvocationStatus.ERROR, String.valueOf(ex.getMessage()), ex);
        }
    }

    /** Exposed so tests and health reporting can see the breaker without reaching through the field. */
    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }
}
