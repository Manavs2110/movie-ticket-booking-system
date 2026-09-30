package com.moviebooking.service.booking.internal.seathold;

import com.moviebooking.common.RedisGuard;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Layer 1 of the seat lock (LLD §5.1): a contention filter. It rejects losing clicks in memory so Postgres
 * sees about one claim per seat. It never decides ownership; if it is unavailable, callers go straight
 * to Postgres ({@link Acquire#bypassed()}).
 * <p>
 * Keys: {@code lock:{show:<showId>}:seat:<seatId>} → bookingId or {@code BOOKED}. The hash tag keeps a
 * show's keys in one Redis Cluster slot so the multi-key scripts work in cluster mode.
 */
@Component
public class RedisSeatLockFilter {

    public record Acquire(List<Long> taken, boolean bypassed) {
        static final Acquire BYPASSED = new Acquire(List.of(), true);
    }

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> acquireAll;
    private final RedisScript<Long> extendIfOwner;
    private final RedisScript<Long> releaseIfOwner;
    private final RedisScript<Long> markBooked;

    @SuppressWarnings("rawtypes")
    public RedisSeatLockFilter(StringRedisTemplate redis, RedisGuard guard, RedisScript<List> acquireAllScript,
                               RedisScript<Long> extendIfOwnerScript, RedisScript<Long> releaseIfOwnerScript,
                               RedisScript<Long> markBookedScript) {
        this.redis = redis;
        this.guard = guard;
        this.acquireAll = acquireAllScript;
        this.extendIfOwner = extendIfOwnerScript;
        this.releaseIfOwner = releaseIfOwnerScript;
        this.markBooked = markBookedScript;
    }

    public static String key(Long showId, Long seatId) {
        return "lock:{show:" + showId + "}:seat:" + seatId;
    }

    /** All or nothing. Returns the seat ids already taken (nothing was set), or none (all set with {@code ttl}). */
    public Acquire tryAcquire(Long showId, List<Long> seatIds, Long bookingId, Duration ttl) {
        return guard.call("tryAcquire", () -> {
            List<?> takenIndexes = redis.execute(acquireAll, keys(showId, seatIds), bookingId.toString(),
                    String.valueOf(ttl.toMillis()));
            List<Long> taken = takenIndexes == null ? List.of() : takenIndexes.stream()
                    .map(i -> seatIds.get(((Number) i).intValue() - 1)).toList();
            return new Acquire(taken, false);
        }, () -> Acquire.BYPASSED);
    }

    public void extend(Long showId, List<Long> seatIds, Long bookingId, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || seatIds.isEmpty()) {
            return;
        }
        guard.run("extend", () -> redis.execute(extendIfOwner, keys(showId, seatIds), bookingId.toString(),
                String.valueOf(ttl.toMillis())));
    }

    public void markBooked(Long showId, List<Long> seatIds, Long bookingId, Instant until) {
        if (seatIds.isEmpty()) {
            return;
        }
        guard.run("markBooked", () -> redis.execute(markBooked, keys(showId, seatIds), bookingId.toString(),
                String.valueOf(until.toEpochMilli())));
    }

    /** Owner-checked release; {@code includeBookedMarkers} for seats this booking owned in Postgres. */
    public void release(Long showId, List<Long> seatIds, Long bookingId, boolean includeBookedMarkers) {
        if (seatIds.isEmpty()) {
            return;
        }
        guard.run("release", () -> redis.execute(releaseIfOwner, keys(showId, seatIds), bookingId.toString(),
                includeBookedMarkers ? "1" : "0"));
    }

    /** Admin cancelled the show: drop every lock key of it (one slot, one DEL). */
    public void releaseShow(Long showId, List<Long> allSeatIds) {
        if (allSeatIds.isEmpty()) {
            return;
        }
        guard.run("releaseShow", () -> redis.delete(keys(showId, allSeatIds)));
    }

    private static List<String> keys(Long showId, List<Long> seatIds) {
        return seatIds.stream().map(id -> key(showId, id)).toList();
    }
}
