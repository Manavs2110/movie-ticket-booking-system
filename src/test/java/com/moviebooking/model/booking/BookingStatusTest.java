package com.moviebooking.model.booking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BookingStatusTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    @Test
    void heldWithFutureExpiryIsHeld() {
        assertThat(BookingStatus.effective(BookingStatus.HELD, NOW.plusSeconds(1), NOW)).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void heldAtOrPastExpiryIsReportedExpired() {
        assertThat(BookingStatus.effective(BookingStatus.HELD, NOW, NOW)).isEqualTo(BookingStatus.EXPIRED);
        assertThat(BookingStatus.effective(BookingStatus.HELD, NOW.minusSeconds(1), NOW)).isEqualTo(BookingStatus.EXPIRED);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"PAYMENT_PENDING", "CONFIRMED", "CANCELLED", "EXPIRED"})
    void otherStatusesAreNeverDerived(BookingStatus stored) {
        // PAYMENT_PENDING in particular must not flip to EXPIRED: money may be in flight.
        assertThat(BookingStatus.effective(stored, NOW.minusSeconds(3600), NOW)).isEqualTo(stored);
    }
}
