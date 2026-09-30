package com.moviebooking.dto.catalog;

import com.moviebooking.model.catalog.Movie;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

public record MovieResponse(Long id, String title, String description, int durationMinutes, String language,
                            List<String> genres, String certificate, LocalDate releaseDate, List<String> cast,
                            String posterUrl, String trailerUrl) {

    public static MovieResponse from(Movie m) {
        return new MovieResponse(m.getId(), m.getTitle(), m.getDescription(), m.getDurationMinutes(),
                m.getLanguage(), split(m.getGenres()), m.getCertificate(), m.getReleaseDate(),
                split(m.getCastMembers()), m.getPosterUrl(), m.getTrailerUrl());
    }

    static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

}
