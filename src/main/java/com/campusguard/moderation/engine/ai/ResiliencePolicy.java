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
 * A deadline, a memory, and the patience to wait out a rate limit — for any call
 * to one model, whatever shape that call takes.
 *
 * <p>Extracted from {@link ResilientChatCompletion} when a second kind of call
 * appeared. The two kinds go to the same endpoint under the same quota, so they
 * have to share one circuit: separate breakers would let a throttled account
 * look like one healthy path and one broken one, which is the same mistake
 * {@code AiEngineConfig} avoids by sharing a port between prompt versions.
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
public class ResiliencePolicy {

    private static final Logger log = LoggerFactory.getLogger(ResiliencePolicy.class);

    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor;
    private final AiProperties properties;

    public ResiliencePolicy(AiProperties properties) {
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

    /**
     * Runs one call under the deadline and the circuit, waiting and trying again
     * when the provider says we are asking too fast, and only then.
     *
     * <p>The wait happens here rather than inside the time limiter on purpose: a
     * backoff counted against the call budget would guarantee the retry times out
     * before it is even sent.
     *
     * <p>Nothing else is retried. A refused credential will still be refused after
     * two seconds, and retrying it just spends the budget twice to learn the same
     * thing.
     */
    public <T> T execute(Callable<T> work) {
        ModelCallException throttled = null;

        for (int attempt = 0; attempt <= properties.rateLimitRetries(); attempt++) {
            if (attempt > 0) {
                backOff(attempt);
            }
            try {
                return callOnce(work);
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

    /** Exposed so tests and health reporting can see the breaker without reaching through the field. */
    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
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

    private <T> T callOnce(Callable<T> work) {
        Callable<T> timed = TimeLimiter.decorateFutureSupplier(timeLimiter, () -> executor.submit(work));

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
}
