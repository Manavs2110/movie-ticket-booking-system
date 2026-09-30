package com.moviebooking.dto.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** One theater in the "theaters and showtimes for a movie" list, with that day's shows. */
public record TheaterShowtimes(Long theaterId, String name, String address, Double distanceKm,
                               List<Showtime> showtimes) {

    public record Showtime(Long showId, Instant startTime, String screenName, BigDecimal regularPrice,
                           BigDecimal premiumPrice, BigDecimal weekendMultiplier) {
    }
}
