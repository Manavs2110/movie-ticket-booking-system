package com.moviebooking.dto.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ScreenRequest(@NotBlank @Size(max = 50) String name, @NotEmpty @Valid List<SeatRowRequest> rows) {
}
