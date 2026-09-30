package com.moviebooking.dto.catalog;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** All optional; only allowed while the show has no bookings. */
public record ShowUpdateRequest(
        OffsetDateTime startTime,
        @DecimalMin(value = "0.01") @DecimalMax("100000") @Digits(integer = 8, fraction = 2) BigDecimal regularPrice,
        @DecimalMin(value = "0.01") @DecimalMax("100000") @Digits(integer = 8, fraction = 2) BigDecimal premiumPrice,
        @DecimalMin(value = "0.10") @DecimalMax("9.99") @Digits(integer = 1, fraction = 2) BigDecimal weekendMultiplier) {
}
