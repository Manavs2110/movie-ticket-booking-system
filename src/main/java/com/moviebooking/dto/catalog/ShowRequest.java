package com.moviebooking.dto.catalog;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ShowRequest(
        @NotNull Long movieId,
        @NotNull Long screenId,
        @NotNull OffsetDateTime startTime,
        @NotNull @DecimalMin(value = "0.01") @DecimalMax("100000") @Digits(integer = 8, fraction = 2) BigDecimal regularPrice,
        @NotNull @DecimalMin(value = "0.01") @DecimalMax("100000") @Digits(integer = 8, fraction = 2) BigDecimal premiumPrice,
        /* optional: omitted = app.show.default-weekend-multiplier (1.25) */
        @DecimalMin(value = "0.10") @DecimalMax("9.99") @Digits(integer = 1, fraction = 2) BigDecimal weekendMultiplier) {
}
