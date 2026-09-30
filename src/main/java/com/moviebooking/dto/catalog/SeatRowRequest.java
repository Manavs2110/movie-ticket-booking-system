package com.moviebooking.dto.catalog;

import com.moviebooking.model.catalog.SeatType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** One row of a layout: "row C has 14 PREMIUM seats numbered 1..14". */
public record SeatRowRequest(
        @NotNull @Pattern(regexp = "^[A-Z]{1,3}$", message = "must be 1-3 upper-case letters") String rowLabel,
        @Min(1) @Max(100) int seatCount,
        @NotNull SeatType seatType) {
}
