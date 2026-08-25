package com.campusguard.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("campusguard.auth-rate-limit")
public record AuthRateLimitProperties(
        int registrationsPerIp,
        int loginsPerIp,
        int loginsPerAccount,
        int refreshesPerIp,
        Duration window) {

    public AuthRateLimitProperties {
        if (registrationsPerIp < 1 || loginsPerIp < 1 || loginsPerAccount < 1 || refreshesPerIp < 1) {
            throw new IllegalStateException("Authentication rate limits must all be positive.");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalStateException("Authentication rate-limit window must be positive.");
        }
    }
}
