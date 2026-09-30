package com.moviebooking.service.booking.internal.seathold;

import com.moviebooking.common.SqlArrays;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Layer 2, the owner (LLD §5.2): the {@code seat_lock} table. Its primary key (show, seat) is the only thing
 * that decides who holds a seat. The lock is data (a held_until timestamp), not a database lock: row locks
 * last only for the milliseconds of the claiming transaction. Every time comparison uses {@code now()}.
 * <p>
 * {@code WHERE booking_id = :bookingId} is the ownership check: once a lock expires and another booking takes
 * the seat, the row carries that booking's id and these statements can't touch it.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcSeatHoldService {

    /** Rows touched by a statement: which show and seats, plus the lock end and DB time where relevant. */
    public record Touched(Long showId, List<Long> seatIds, Instant heldUntil, Instant dbNow) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSeatHoldService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Cheap, lock-free read. Only needed when the Redis layer was bypassed. */
    public List<Long> findTaken(Long showId, Collection<Long> seatIds) {
        return jdbc.queryForList("""
                SELECT seat_id FROM seat_lock
                WHERE show_id = :showId AND seat_id = ANY(:seatIds) AND (booked OR held_until > now())
                ORDER BY seat_id
                """, new MapSqlParameterSource("showId", showId)
                .addValue("seatIds", SqlArrays.bigintArray(seatIds), Types.ARRAY), Long.class);
    }

    /** Atomic insert-or-overwrite of each seat in ascending order (consistent lock order → no deadlocks). */
    public List<Long> hold(Long showId, Long bookingId, List<Long> sortedSeatIds, Duration holdFor) {
        return jdbc.queryForList("""
                INSERT INTO seat_lock AS s (show_id, seat_id, booking_id, booked, held_until)
                SELECT :showId, x.seat_id, :bookingId, false, now() + CAST(:holdFor AS interval)
                FROM unnest(CAST(:seatIds AS bigint[])) WITH ORDINALITY AS x(seat_id, ord)
                ORDER BY x.ord
                ON CONFLICT (show_id, seat_id) DO UPDATE
                   SET booking_id = EXCLUDED.booking_id,
                       held_until = EXCLUDED.held_until
                 WHERE NOT s.booked AND s.held_until <= now()
                RETURNING seat_id
                """, new MapSqlParameterSource("showId", showId)
                .addValue("bookingId", bookingId)
                .addValue("holdFor", interval(holdFor))
                .addValue("seatIds", SqlArrays.bigintArray(sortedSeatIds), Types.ARRAY), Long.class);
    }

    /** Guarantees at least {@code window} left on every seat this booking still validly holds. */
    public Touched extend(Long bookingId, Duration window) {
        return touched(jdbc.queryForList("""
                UPDATE seat_lock SET held_until = GREATEST(held_until, now() + CAST(:window AS interval))
                WHERE booking_id = :bookingId AND NOT booked AND held_until > now()
                RETURNING show_id, seat_id, held_until, now() AS db_now
                """, new MapSqlParameterSource("bookingId", bookingId).addValue("window", interval(window))));
    }

    public Touched confirm(Long bookingId) {
        return touched(jdbc.queryForList("""
                UPDATE seat_lock SET booked = true
                WHERE booking_id = :bookingId AND NOT booked AND held_until > now()
                RETURNING show_id, seat_id, held_until, now() AS db_now
                """, new MapSqlParameterSource("bookingId", bookingId)));
    }

    public Touched release(Long bookingId) {
        return touched(jdbc.queryForList("""
                DELETE FROM seat_lock WHERE booking_id = :bookingId
                RETURNING show_id, seat_id, held_until, now() AS db_now
                """, new MapSqlParameterSource("bookingId", bookingId)));
    }

    private static Touched touched(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return new Touched(null, List.of(), null, null);
        }
        Long showId = ((Number) rows.get(0).get("show_id")).longValue();
        List<Long> seatIds = rows.stream().map(r -> ((Number) r.get("seat_id")).longValue()).sorted().toList();
        Instant heldUntil = rows.stream().map(r -> instant(r.get("held_until"))).min(Instant::compareTo).orElse(null);
        return new Touched(showId, seatIds, heldUntil, instant(rows.get(0).get("db_now")));
    }

    private static Instant instant(Object ts) {
        return ts instanceof Timestamp t ? t.toInstant()
                : ts instanceof OffsetDateTime o ? o.toInstant() : null;
    }

    private static String interval(Duration d) {
        return d.toMillis() + " milliseconds";
    }
}
