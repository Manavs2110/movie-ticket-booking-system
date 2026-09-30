package com.moviebooking.service.booking.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.common.RedisGuard;
import com.moviebooking.config.AppProperties;
import com.moviebooking.dto.booking.SeatMapResponse.SeatState;
import com.moviebooking.dto.booking.SeatMapResponse.SeatView;
import com.moviebooking.dto.booking.SeatMapResponse;
import com.moviebooking.model.catalog.SeatLayout;
import com.moviebooking.model.catalog.SeatType;
import com.moviebooking.model.catalog.ShowDetails;
import com.moviebooking.model.catalog.ShowStatus;
import com.moviebooking.service.booking.SeatMapService;
import com.moviebooking.service.catalog.ScreenService;
import com.moviebooking.service.catalog.ShowService;
import com.moviebooking.service.pricing.PricingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live seat map with ETag (LLD §9). A hot show gets thousands of polls per second, so the built map sits in a
 * 2-second Redis micro-cache: at most one Postgres rebuild per show every 2 s. The ETag is computed at build
 * time (never stored in Postgres): a lock expiring writes nothing, yet still changes the map within 2 s.
 * The map is only a display: the lock request is the final answer, so this staleness is safe.
 */
@Service
public class SeatMapServiceImpl implements SeatMapService {

    /** What the micro-cache stores. */
    record Cached(String etag, SeatMapResponse body) {
    }

    private static final Duration BUILD_GUARD_TTL = Duration.ofSeconds(1);
    private static final long WAIT_FOR_BUILDER_MS = 50;

    private final JdbcTemplate jdbc;
    private final ShowService showService;
    private final ScreenService screenService;
    private final PricingService pricingService;
    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final ObjectMapper objectMapper;
    private final Duration microCacheTtl;

    public SeatMapServiceImpl(JdbcTemplate jdbc, ShowService showService, ScreenService screenService,
                          PricingService pricingService, StringRedisTemplate redis, RedisGuard guard,
                          ObjectMapper objectMapper, AppProperties props) {
        this.jdbc = jdbc;
        this.showService = showService;
        this.screenService = screenService;
        this.pricingService = pricingService;
        this.redis = redis;
        this.guard = guard;
        this.objectMapper = objectMapper;
        this.microCacheTtl = props.seatMap().microCacheTtl();
    }

    @Override
    public Result seatMap(Long showId, String ifNoneMatch) {
        Cached map = readCache(showId);
        if (map == null) {
            // Stampede guard: one caller rebuilds; the others wait briefly and read what it stored.
            boolean builder = guard.call("seatmap guard", () -> Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(SeatMapService.cacheKey(showId) + ":build", "1", BUILD_GUARD_TTL)), () -> true);
            if (!builder) {
                sleep();
                map = readCache(showId);
            }
            if (map == null) {
                map = build(showId);
                writeCache(showId, map);
            }
        }
        return matches(ifNoneMatch, map.etag()) ? new NotModified(map.etag()) : new Fresh(map.etag(), map.body());
    }

    /** After a lock, confirm or cancel on this show, so the acting user sees the change immediately. */
    @Override
    public void evict(Long showId) {
        guard.run("seatmap evict", () -> redis.delete(SeatMapService.cacheKey(showId)));
    }

    Cached build(Long showId) {
        ShowDetails show = showService.details(showId);

        record Fingerprint(String takenHash, Instant nextChangeAt, Instant dbNow) {
        }
        Fingerprint fp = jdbc.queryForObject("""
                SELECT md5(coalesce(string_agg(seat_id || ':' || CASE WHEN booked THEN 'B' ELSE 'H' END,
                                               ',' ORDER BY seat_id), '')) AS taken_hash,
                       min(held_until) FILTER (WHERE NOT booked)        AS next_change_at,
                       now()                                           AS db_now
                FROM seat_lock
                WHERE show_id = ? AND (booked OR held_until > now())
                """, (rs, i) -> new Fingerprint(rs.getString("taken_hash"), instant(rs.getTimestamp("next_change_at")),
                instant(rs.getTimestamp("db_now"))), showId);
        String etag = "\"L%d-S%d-%s\"".formatted(show.layoutVersion(), show.version(), fp.takenHash());

        SeatLayout layout = screenService.layout(show.screenId(), show.layoutVersion());
        Map<Long, Boolean> taken = new HashMap<>();   // seatId → booked?
        jdbc.query("SELECT seat_id, booked FROM seat_lock WHERE show_id = ? AND (booked OR held_until > now())",
                rs -> {
                    taken.put(rs.getLong("seat_id"), rs.getBoolean("booked"));
                }, showId);

        Map<SeatType, BigDecimal> priceByType = new EnumMap<>(SeatType.class);
        for (SeatType type : SeatType.values()) {
            priceByType.put(type, pricingService.seatPrice(show, type));
        }
        List<SeatView> seats = layout.seats().stream().map(s -> {
            SeatState state;
            if (!s.active()) {
                state = SeatState.BLOCKED;
            } else if (taken.containsKey(s.id())) {
                state = taken.get(s.id()) ? SeatState.BOOKED : SeatState.HELD;
            } else {
                state = SeatState.AVAILABLE;
            }
            return new SeatView(s.id(), s.label(), s.rowLabel(), s.seatNumber(), s.seatType(), state,
                    priceByType.get(s.seatType()));
        }).toList();

        boolean bookable = show.status() == ShowStatus.SCHEDULED && show.startTime().isAfter(fp.dbNow());
        return new Cached(etag, new SeatMapResponse(show.id(), show.status(), bookable, fp.nextChangeAt(), seats));
    }

    private Cached readCache(Long showId) {
        return guard.call("seatmap get", () -> {
            String json = redis.opsForValue().get(SeatMapService.cacheKey(showId));
            return json == null ? null : parse(json);
        }, () -> null);
    }

    private void writeCache(Long showId, Cached map) {
        guard.run("seatmap put", () -> redis.opsForValue().set(SeatMapService.cacheKey(showId), toJson(map), microCacheTtl));
    }

    private Cached parse(String json) {
        try {
            return objectMapper.readValue(json, Cached.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String toJson(Cached map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(WAIT_FOR_BUILDER_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.trim();
            if (c.startsWith("W/")) {
                c = c.substring(2);
            }
            if (c.equals(etag) || c.equals("*")) {
                return true;
            }
        }
        return false;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
