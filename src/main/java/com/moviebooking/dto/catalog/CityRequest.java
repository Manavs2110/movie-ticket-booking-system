package com.moviebooking.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CityRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 100) String state) {
}
