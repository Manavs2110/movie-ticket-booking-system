package com.moviebooking.model.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountCodeTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");

    private static DiscountCode code(DiscountType type, String value, String cap, Integer limit, boolean active) {
        DiscountCode dc = new DiscountCode("TEST");
        dc.update(type, new BigDecimal(value), cap == null ? null : new BigDecimal(cap), BigDecimal.ZERO, FROM, TO,
                limit, 1, active);
        return dc;
    }

    @Test
    void flatDiscountIsCappedAtSubtotal() {
        DiscountCode flat = code(DiscountType.FLAT, "50", null, null, true);
        assertThat(flat.discountFor(new BigDecimal("500.00"))).isEqualByComparingTo("50.00");
        assertThat(flat.discountFor(new BigDecimal("30.00"))).isEqualByComparingTo("30.00");
    }

    @Test
    void percentDiscountUsesCapAndRoundsHalfUp() {
        DiscountCode pct = code(DiscountType.PERCENT, "20", "150", null, true);
        assertThat(pct.discountFor(new BigDecimal("500.00"))).isEqualByComparingTo("100.00");
        assertThat(pct.discountFor(new BigDecimal("1000.00"))).isEqualByComparingTo("150.00");
        assertThat(pct.discountFor(new BigDecimal("333.33"))).isEqualByComparingTo("66.67");
    }

    @Test
    void percentWithoutCapIsUncapped() {
        DiscountCode pct = code(DiscountType.PERCENT, "10", null, null, true);
        assertThat(pct.discountFor(new BigDecimal("10000.00"))).isEqualByComparingTo("1000.00");
    }

    @Test
    void validityWindowIsHalfOpenAndRespectsActiveFlag() {
        DiscountCode dc = code(DiscountType.FLAT, "10", null, null, true);
        assertThat(dc.isCurrentlyValid(FROM)).isTrue();
        assertThat(dc.isCurrentlyValid(FROM.minusSeconds(1))).isFalse();
        assertThat(dc.isCurrentlyValid(TO)).isFalse();
        assertThat(code(DiscountType.FLAT, "10", null, null, false).isCurrentlyValid(FROM)).isFalse();
    }

    @Test
    void usageLimit() {
        assertThat(code(DiscountType.FLAT, "10", null, null, true).isUsedUp()).isFalse();
        assertThat(code(DiscountType.FLAT, "10", null, 0, true).isUsedUp()).isTrue();
    }
}
