package com.moviebooking.dto.catalog;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TheaterRequest(
        @NotNull Long cityId,
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 300) String address,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        Long refundPolicyId,
        Boolean active) {
}
