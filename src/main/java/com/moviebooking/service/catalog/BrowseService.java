package com.moviebooking.service.catalog;

import com.moviebooking.common.PageResponse;
import com.moviebooking.dto.catalog.MovieSummary;
import com.moviebooking.dto.catalog.TheaterShowtimes;

import java.time.LocalDate;

/**
 * Public browse queries (LLD §8). Indexed, city-scoped SQL; results are cached for a short time
 * because listings may be slightly stale (availability is always re-checked at hold time).
 */
public interface BrowseService {

    /** §8.1 Movies showing in a city on a date, with optional language and genre filters. */
    PageResponse<MovieSummary> moviesInCity(Long cityId, LocalDate date, String language, String genre, int page, int size);

    /**
     * §8.2 Theaters showing a movie in a city on a date, each with its showtimes.
     * With lat/lng: bounding-box filter, then sorted by great-circle distance ("near me"); not cached.
     */
    PageResponse<TheaterShowtimes> showsForMovie(Long movieId, Long cityId, LocalDate date, Double lat, Double lng, Double radiusKm, int page, int size);
}
