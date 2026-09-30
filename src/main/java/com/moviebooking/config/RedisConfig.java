package com.moviebooking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Lua scripts for the seat-lock filter (LLD §5.1) and the rate limiter (LLD §11.3).
 * Each script runs atomically in Redis; Spring sends EVALSHA and falls back to EVAL once.
 */
@Configuration
public class RedisConfig {

    /** All or nothing. KEYS = seat keys, ARGV[1] = bookingId, ARGV[2] = ttlMs. Returns 1-based indexes of taken keys. */
    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public RedisScript<List> acquireAllScript() {
        return (RedisScript) new DefaultRedisScript<>("""
                local taken = {}
                for i, k in ipairs(KEYS) do
                  if redis.call('EXISTS', k) == 1 then taken[#taken + 1] = i end
                end
                if #taken > 0 then return taken end
                for _, k in ipairs(KEYS) do redis.call('SET', k, ARGV[1], 'PX', ARGV[2]) end
                return {}
                """, List.class);
    }

    /** ARGV[1] = bookingId, ARGV[2] = ttlMs. Extends only keys this booking owns. */
    @Bean
    public RedisScript<Long> extendIfOwnerScript() {
        return new DefaultRedisScript<>("""
                local n = 0
                for _, k in ipairs(KEYS) do
                  if redis.call('GET', k) == ARGV[1] then redis.call('PEXPIRE', k, ARGV[2]); n = n + 1 end
                end
                return n
                """, Long.class);
    }

    /**
     * ARGV[1] = bookingId, ARGV[2] = '1' to also delete BOOKED markers. The caller only passes seats this booking
     * owned in Postgres, so a BOOKED marker on them is this booking's own.
     */
    @Bean
    public RedisScript<Long> releaseIfOwnerScript() {
        return new DefaultRedisScript<>("""
                local n = 0
                for _, k in ipairs(KEYS) do
                  local v = redis.call('GET', k)
                  if v == ARGV[1] or (ARGV[2] == '1' and v == 'BOOKED') then redis.call('DEL', k); n = n + 1 end
                end
                return n
                """, Long.class);
    }

    /** ARGV[1] = bookingId, ARGV[2] = show end (epoch ms). Turns the lock into a BOOKED marker until show end. */
    @Bean
    public RedisScript<Long> markBookedScript() {
        return new DefaultRedisScript<>("""
                local n = 0
                for _, k in ipairs(KEYS) do
                  local v = redis.call('GET', k)
                  if v == false or v == ARGV[1] then redis.call('SET', k, 'BOOKED', 'PXAT', ARGV[2]); n = n + 1 end
                end
                return n
                """, Long.class);
    }

    /** Fixed one-minute window. KEYS[1] = rl:<user>:<endpoint>:<epochMinute>, ARGV[1] = limit. 1 = allowed. */
    @Bean
    public RedisScript<Long> rateLimitScript() {
        return new DefaultRedisScript<>("""
                local n = redis.call('INCR', KEYS[1])
                if n == 1 then redis.call('EXPIRE', KEYS[1], 60) end
                if n <= tonumber(ARGV[1]) then return 1 else return 0 end
                """, Long.class);
    }
}
