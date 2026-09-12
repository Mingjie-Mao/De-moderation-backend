package com.campusguard.auth;

import com.campusguard.common.TooManyRequestsException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class RequestRateLimiter {

    private final NamedParameterJdbcTemplate jdbc;

    public RequestRateLimiter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${campusguard.auth-rate-limit.cleanup-interval:1h}",
            initialDelayString = "${campusguard.auth-rate-limit.cleanup-interval:1h}")
    @Transactional
    public void removeExpiredBuckets() {
        jdbc.update(
                "delete from request_rate_limits where updated_at < :threshold",
                Map.of("threshold", Timestamp.from(Instant.now().minus(Duration.ofDays(2)))));
    }

    /**
     * Counts an attempt against a bucket, and refuses once the bucket is full.
     *
     * <p>Lives in the auth package because that is where it was first needed, and
     * is not about authentication: the bucket is whatever the caller names. What
     * makes it the right tool outside auth is that it counts attempts rather than
     * successes, which is what you want for anything that costs something to try.
     */
    @Transactional
    public void consume(String scope, String identity, int limit, Duration window, String message) {
        Instant now = Instant.now();
        String bucket = hash(scope + ":" + String.valueOf(identity).strip().toLowerCase(java.util.Locale.ROOT));
        Integer count = jdbc.queryForObject(
                """
                insert into request_rate_limits (bucket_key, window_start, request_count, updated_at)
                values (:bucket, :now, 1, :now)
                on conflict (bucket_key) do update set
                    request_count = case
                        when request_rate_limits.window_start <= :cutoff then 1
                        else request_rate_limits.request_count + 1
                    end,
                    window_start = case
                        when request_rate_limits.window_start <= :cutoff then :now
                        else request_rate_limits.window_start
                    end,
                    updated_at = :now
                returning request_count
                """,
                Map.of(
                        "bucket", bucket,
                        "now", Timestamp.from(now),
                        "cutoff", Timestamp.from(now.minus(window))),
                Integer.class);

        if (count != null && count > limit) {
            throw new TooManyRequestsException(message);
        }
    }

    /** The original signature, with the message the authentication call sites want. */
    @Transactional
    public void consume(String scope, String identity, int limit, Duration window) {
        consume(scope, identity, limit, window,
                "Too many authentication attempts. Try again after " + window + ".");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is required by the JVM.", ex);
        }
    }
}
