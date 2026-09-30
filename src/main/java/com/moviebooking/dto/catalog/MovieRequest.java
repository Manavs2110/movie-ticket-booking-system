package com.moviebooking.dto.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record MovieRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @Min(1) @Max(600) int durationMinutes,
        @NotBlank @Size(max = 30) String language,
        @NotEmpty List<@NotBlank @Pattern(regexp = "^[A-Za-z -]{1,30}$") String> genres,
        @NotNull @Pattern(regexp = "^(U|UA|A)$", message = "must be U, UA or A") String certificate,
        @NotNull LocalDate releaseDate,
        List<String> cast,
        @Size(max = 500) String posterUrl,
        @Size(max = 500) String trailerUrl) {
}
