package com.moviebooking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;

@ConfigurationProperties("app")
public record AppProperties(
        String zone,
        BookingProps booking,
        SeatLockProps seatLock,
        SeatMapProps seatMap,
        RateLimitProps rateLimit,
        ShowProps show,
        ReminderProps reminder,
        OutboxProps outbox,
        GeoProps geo,
        CacheTtlProps cacheTtl) {

    /** holdDuration = seat lock (10 min); paymentWindow = minimum lock left once payment starts (5 min). */
    public record BookingProps(Duration holdDuration, Duration paymentWindow, int maxSeatsPerBooking) {
    }

    /** TTL of a Redis lock key before the Postgres claim commits: bounds a ghost lock after a crash. */
    public record SeatLockProps(Duration redisInitialTtl) {
    }

    public record SeatMapProps(Duration microCacheTtl) {
    }

    public record RateLimitProps(int holdPerMinute, int payPerMinute, int defaultPerMinute) {
    }

    public record ShowProps(Duration cleaningBuffer, BigDecimal defaultWeekendMultiplier) {
    }

    public record ReminderProps(Duration leadTime) {
    }

    public record OutboxProps(Duration pollInterval, int batchSize, int maxAttempts, String topic, Duration ackTimeout) {
    }

    public record GeoProps(double defaultRadiusKm) {
    }

    public record CacheTtlProps(Duration cities, Duration movie, Duration moviesInCity, Duration showsForMovie,
                                Duration seatLayout) {
    }

    /** Business time zone: weekend pricing, "shows on a date", display of times. */
    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
