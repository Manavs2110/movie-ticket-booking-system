package com.moviebooking.dto.booking;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Seat count upper bound is enforced by the service (configurable, default 10). */
public record HoldRequest(
        @NotNull Long showId,
        @NotEmpty @Size(max = 50) List<@NotNull Long> seatIds,
        @Size(max = 30) String discountCode) {
}
