package com.moviebooking.dto.pricing;

import com.moviebooking.model.pricing.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record DiscountCodeRequest(
        @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{3,30}$", message = "must be 3-30 letters, digits, _ or -") String code,
        @NotNull DiscountType type,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal value,
        @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal maxDiscount,
        @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal minOrder,
        @NotNull OffsetDateTime validFrom,
        @NotNull OffsetDateTime validTo,
        @Min(1) Integer usageLimit,
        @Min(1) Integer perUserLimit,
        Boolean active) {
}
