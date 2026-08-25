package com.campusguard.moderation.engine.ai;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
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
    private final AiProperties properties;

    public ResilientChatCompletion(ChatCompletionPort delegate, AiProperties properties) {
        this.delegate = delegate;
        this.properties = properties;

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

    /**
     * Waits and tries again when the provider says we are asking too fast, and
     * only then.
     *
     * <p>The wait happens here rather than inside the time limiter on purpose: a
     * backoff counted against the call budget would guarantee the retry times out
     * before it is even sent.
     *
     * <p>Nothing else is retried. A refused credential will still be refused after
     * two seconds, and retrying it just spends the budget twice to learn the same
     * thing.
     */
    @Override
    public CompletionResult complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, java.util.List.of());
    }

    @Override
    public CompletionResult complete(
            String systemPrompt,
            String userPrompt,
            java.util.List<com.campusguard.moderation.engine.ModerationRequest.MediaInput> media) {
        ModelCallException throttled = null;

        for (int attempt = 0; attempt <= properties.rateLimitRetries(); attempt++) {
            if (attempt > 0) {
                backOff(attempt);
            }
            try {
                return callOnce(systemPrompt, userPrompt, media);
            } catch (ModelCallException ex) {
                if (ex.status() != InvocationStatus.RATE_LIMITED) {
                    throw ex;
                }
                throttled = ex;
                log.warn("Rate limited by the model provider, attempt {} of {}.",
                        attempt + 1, properties.rateLimitRetries() + 1);
            }
        }

        throw throttled;
    }

    /**
     * Exponential, with jitter. Without the jitter a batch that all hit the limit
     * at the same moment would wait the same interval and arrive together again,
     * reproducing the collision at every step.
     */
    private void backOff(int attempt) {
        long base = properties.rateLimitBackoff().toMillis() * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);

        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelCallException(
                    InvocationStatus.RATE_LIMITED, "Interrupted while backing off from a rate limit.", ex);
        }
    }

    private CompletionResult callOnce(
            String systemPrompt,
            String userPrompt,
            java.util.List<com.campusguard.moderation.engine.ModerationRequest.MediaInput> media) {
        Callable<CompletionResult> timed = TimeLimiter.decorateFutureSupplier(
                timeLimiter, () -> executor.submit(() -> delegate.complete(systemPrompt, userPrompt, media)));

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
