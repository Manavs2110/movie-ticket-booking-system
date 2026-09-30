package com.moviebooking.service.pricing.internal;

import com.moviebooking.model.catalog.SeatType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class PricingServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal REGULAR = new BigDecimal("200.00");
    private static final BigDecimal PREMIUM = new BigDecimal("350.00");
    private static final BigDecimal WEEKEND = new BigDecimal("1.25");

    @ParameterizedTest(name = "{0} at {1} → {2}")
    @CsvSource({
            // 2026-10-01 is a Thursday, 2026-10-03 a Saturday, 2026-10-04 a Sunday (IST)
            "REGULAR, 2026-10-01T13:30:00Z, 200.00",
            "PREMIUM, 2026-10-01T13:30:00Z, 350.00",
            "REGULAR, 2026-10-03T13:30:00Z, 250.00",
            "PREMIUM, 2026-10-04T13:30:00Z, 437.50",
            // Friday 23:30 IST is still Friday even though it's Friday 18:00 UTC
            "REGULAR, 2026-10-02T18:00:00Z, 200.00",
            // Saturday 00:30 IST is Friday 19:00 UTC: the business time zone decides
            "REGULAR, 2026-10-02T19:00:00Z, 250.00"
    })
    void seatTypePriceTimesWeekendMultiplier(SeatType type, Instant start, BigDecimal expected) {
        BigDecimal price = PricingServiceImpl.seatPrice(REGULAR, PREMIUM, WEEKEND, start, type, IST);
        assertThat(price).isEqualByComparingTo(expected);
        assertThat(price.scale()).isEqualTo(2);
    }

    @ParameterizedTest(name = "weekend ×{0} on a Saturday premium seat → {1}")
    @CsvSource({"1.00, 350.00", "1.40, 490.00", "1.10, 385.00"})
    void eachShowHasItsOwnWeekendMultiplier(BigDecimal multiplier, BigDecimal expected) {
        assertThat(PricingServiceImpl.seatPrice(REGULAR, PREMIUM, multiplier, Instant.parse("2026-10-03T13:30:00Z"),
                SeatType.PREMIUM, IST)).isEqualByComparingTo(expected);
    }

    @Test
    void roundsHalfUp() {
        // 299.99 × 1.25 = 374.9875
        assertThat(PricingServiceImpl.seatPrice(REGULAR, new BigDecimal("299.99"), WEEKEND,
                Instant.parse("2026-10-03T13:30:00Z"), SeatType.PREMIUM, IST)).isEqualByComparingTo("374.99");
    }
}
