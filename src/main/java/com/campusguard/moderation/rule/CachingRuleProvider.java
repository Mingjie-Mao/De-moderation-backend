package com.campusguard.moderation.rule;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rules are read on every case and every evaluation sample, and change perhaps
 * monthly. Reading them from the database each time would put a query in front
 * of every judgement, and a two-hundred-sample evaluation run would spend most
 * of its queries re-fetching three rows.
 *
 * <p>The cache is deliberately time-based rather than invalidated on write:
 * nothing in the system edits rules yet, and a cache with no writer to hook into
 * is better off with an expiry than with an invalidation path that is never
 * exercised and therefore never known to work.
 */
@Component
public class CachingRuleProvider implements RuleProvider {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final ModerationRuleRepository repository;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public CachingRuleProvider(ModerationRuleRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModerationRule> activeRules() {
        Snapshot current = snapshot.get();
        if (current != null && current.isFresh()) {
            return current.rules();
        }

        // A concurrent miss may load twice. That is a duplicated read of three
        // rows, which is cheaper than the lock needed to prevent it.
        List<ModerationRule> loaded = List.copyOf(repository.findByActiveTrueOrderByCodeAsc());
        snapshot.set(new Snapshot(loaded, Instant.now().plus(TTL)));
        return loaded;
    }

    /** Exposed for tests that change rules and need the next read to see them. */
    public void invalidate() {
        snapshot.set(null);
    }

    private record Snapshot(List<ModerationRule> rules, Instant expiresAt) {

        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
