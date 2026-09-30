package com.moviebooking.dto.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AddSeatRowsRequest(@NotEmpty @Valid List<SeatRowRequest> rows) {
}
