package com.moviebooking.common;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Every Redis call on the booking path goes through the {@code redis} circuit breaker (HLD §9.5).
 * Redis never decides anything that matters, so every failure falls back: the seat-lock filter is
 * skipped (Postgres decides alone), the micro-cache is skipped, and rate limiting fails open.
 */
@Component
public class RedisGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);

    private final CircuitBreaker breaker;

    public RedisGuard(CircuitBreakerRegistry registry) {
        this.breaker = registry.circuitBreaker("redis");
    }

    public <T> T call(String what, Supplier<T> op, Supplier<T> fallback) {
        try {
            return breaker.executeSupplier(op);
        } catch (CallNotPermittedException open) {
            return fallback.get();
        } catch (RuntimeException e) {
            log.warn("Redis {} failed, falling back: {}", what, e.getMessage());
            return fallback.get();
        }
    }

    /** Best effort: used after a Postgres commit, where a Redis failure must never undo anything. */
    public void run(String what, Runnable op) {
        call(what, () -> {
            op.run();
            return null;
        }, () -> null);
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }
}
