package com.moviebooking.dto.catalog;

import java.math.BigDecimal;
import java.util.List;

public record MovieSummary(Long id, String title, int durationMinutes, String language, List<String> genres,
                           String certificate, String posterUrl) {

    public static MovieSummary of(Long id, String title, int durationMinutes, String language, String genresCsv,
                                  String certificate, String posterUrl) {
        return new MovieSummary(id, title, durationMinutes, language, MovieResponse.split(genresCsv), certificate,
                posterUrl);
    }
}
