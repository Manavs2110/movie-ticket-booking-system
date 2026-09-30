package com.moviebooking.common.ratelimit;

import com.moviebooking.common.RedisGuard;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/** Fixed one-minute window per user and endpoint, one round trip (LLD §11.3). Fails open. */
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final RedisScript<Long> script;
    private final Clock clock = Clock.systemUTC();

    public RateLimiter(StringRedisTemplate redis, RedisGuard guard, RedisScript<Long> rateLimitScript) {
        this.redis = redis;
        this.guard = guard;
        this.script = rateLimitScript;
    }

    public boolean tryAcquire(Long userId, String endpoint, int limitPerMinute) {
        long minute = clock.millis() / 60_000;
        String key = "rl:" + userId + ":" + endpoint + ":" + minute;
        return guard.call("rateLimit", () -> Long.valueOf(1).equals(
                redis.execute(script, List.of(key), String.valueOf(limitPerMinute))), () -> true);
    }

    /** Seconds until the current window ends (the Retry-After value). */
    public long secondsUntilNextWindow() {
        return 60 - (clock.millis() / 1000) % 60;
    }
}
