package com.moviebooking.support;

import com.moviebooking.common.DbClock;
import com.moviebooking.dto.auth.RegisterRequest;
import com.moviebooking.dto.auth.UserResponse;
import com.moviebooking.dto.catalog.ScreenRequest;
import com.moviebooking.dto.catalog.SeatResponse;
import com.moviebooking.dto.catalog.SeatRowRequest;
import com.moviebooking.dto.catalog.ShowRequest;
import com.moviebooking.model.catalog.SeatType;
import com.moviebooking.service.auth.AppUserDetailsService;
import com.moviebooking.service.booking.SeatMapService;
import com.moviebooking.service.booking.internal.seathold.RedisSeatLockFilter;
import com.moviebooking.service.catalog.ScreenService;
import com.moviebooking.service.catalog.ShowService;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Builds isolated test data (own users, screens and shows) so tests never interfere with each other. */
@TestComponent
public class TestFixtures {

    public static final String PASSWORD = "Password@123";
    public static final long PVR_THEATER_ID = 1;      // seeded, uses the default "Standard" refund policy
    public static final long INOX_THEATER_ID = 2;     // seeded, overrides with the "Flexible" policy
    public static final long MOVIE_120_MIN = 3;       // seeded "Chai & Chaos"

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final AppUserDetailsService users;
    private final ScreenService screens;
    private final ShowService shows;
    private final JdbcTemplate jdbc;
    private final DbClock dbClock;
    private final StringRedisTemplate redis;
    private final PasswordEncoder passwordEncoder;
    private String passwordHash;

    public TestFixtures(AppUserDetailsService users, ScreenService screens, ShowService shows, JdbcTemplate jdbc,
                        DbClock dbClock, StringRedisTemplate redis, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.screens = screens;
        this.shows = shows;
        this.jdbc = jdbc;
        this.dbClock = dbClock;
        this.redis = redis;
        this.passwordEncoder = passwordEncoder;
    }

    public record Customer(Long id, String email) {
    }

    /** A show on a brand-new screen: rows A-C × 10 REGULAR, row D × 10 PREMIUM. */
    public record TestShow(Long showId, Long screenId, List<Long> seatIds) {
        public Long seat(int index) {
            return seatIds.get(index);
        }
    }

    public Customer customer() {
        String email = "user-" + UUID.randomUUID() + "@test.com";
        UserResponse user = users.register(new RegisterRequest(email, PASSWORD, "Test User"));
        return new Customer(user.id(), email);
    }

    /** Many customers at once (one BCrypt hash, bulk insert): for the big contention tests. */
    public List<Customer> customers(int n) {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);
        }
        List<Customer> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String email = "bulk-" + UUID.randomUUID() + "@test.com";
            Long id = jdbc.queryForObject("""
                    INSERT INTO app_user (email, password_hash, full_name, role) VALUES (?, ?, 'Bulk User', 'CUSTOMER')
                    RETURNING id
                    """, Long.class, email, passwordHash);
            result.add(new Customer(id, email));
        }
        return result;
    }

    public TestShow show(Duration startsIn) {
        return show(PVR_THEATER_ID, startsIn);
    }

    public TestShow show(long theaterId, Duration startsIn) {
        Long screenId = screens.create(theaterId, new ScreenRequest("Test " + SEQ.incrementAndGet() + "-" + UUID.randomUUID()
                .toString().substring(0, 8), List.of(
                new SeatRowRequest("A", 10, SeatType.REGULAR),
                new SeatRowRequest("B", 10, SeatType.REGULAR),
                new SeatRowRequest("C", 10, SeatType.REGULAR),
                new SeatRowRequest("D", 10, SeatType.PREMIUM)))).id();
        Long showId = shows.create(new ShowRequest(MOVIE_120_MIN, screenId,
                dbClock.now().plus(startsIn).atOffset(ZoneOffset.UTC), new BigDecimal("200.00"),
                new BigDecimal("300.00"), null)).id();
        List<Long> seatIds = screens.seats(screenId).stream().map(SeatResponse::id).toList();
        return new TestShow(showId, screenId, seatIds);
    }

    /**
     * The lock simply runs out: Postgres timestamps move into the past, and the Redis keys vanish as their
     * TTL would (their TTL matches the Postgres lock), plus the show's seat-map micro-cache.
     */
    public void expireHold(Long bookingId) {
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT show_id, seat_id FROM seat_lock WHERE booking_id = ?", bookingId)) {
            Long showId = ((Number) row.get("show_id")).longValue();
            redis.delete(RedisSeatLockFilter.key(showId, ((Number) row.get("seat_id")).longValue()));
            redis.delete(SeatMapService.cacheKey(showId));
        }
        jdbc.update("UPDATE seat_lock SET held_until = now() - interval '1 second' WHERE booking_id = ?", bookingId);
        jdbc.update("UPDATE booking SET hold_expires_at = now() - interval '1 second' WHERE id = ?", bookingId);
    }

    /** Pretends the show has already started. */
    public void startShow(Long showId) {
        jdbc.update("UPDATE show SET start_time = now() - interval '1 minute', end_time = now() + interval '2 hours' WHERE id = ?",
                showId);
    }

    /** As if the 2-second seat-map micro-cache had just expired. */
    public void clearSeatMapCache(Long showId) {
        redis.delete(SeatMapService.cacheKey(showId));
    }

    public Long discountCode(String code, String type, String value, Integer usageLimit, int perUserLimit) {
        return jdbc.queryForObject("""
                INSERT INTO discount_code (code, type, value, min_order, valid_from, valid_to, usage_limit, per_user_limit)
                VALUES (?, ?, ?, 0, now() - interval '1 day', now() + interval '1 day', ?, ?) RETURNING id
                """, Long.class, code, type, new BigDecimal(value), usageLimit, perUserLimit);
    }

    public int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    public String string(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public StringRedisTemplate redis() {
        return redis;
    }
}
