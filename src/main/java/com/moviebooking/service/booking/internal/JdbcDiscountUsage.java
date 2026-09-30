package com.moviebooking.service.booking.internal;

import com.moviebooking.service.pricing.DiscountUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Booking-side answer to "how often has this customer used this code?" (per-user discount limit). */
@Component
class JdbcDiscountUsage implements DiscountUsage {

    private final JdbcTemplate jdbc;

    JdbcDiscountUsage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int redeemedBy(Long userId, Long discountCodeId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM booking WHERE user_id = ? AND discount_code_id = ? AND discount_redeemed",
                Integer.class, userId, discountCodeId);
        return n == null ? 0 : n;
    }
}
