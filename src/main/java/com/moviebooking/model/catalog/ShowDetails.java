package com.moviebooking.model.catalog;

import java.math.BigDecimal;
import java.time.Instant;

/** Read model of a show joined with its movie, screen and theater: what the booking module needs. */
public record ShowDetails(
        Long id,
        Long movieId,
        String movieTitle,
        Long screenId,
        String screenName,
        int layoutVersion,
        Long theaterId,
        String theaterName,
        Long cityId,
        Long refundPolicyId,
        Instant startTime,
        Instant endTime,
        BigDecimal regularPrice,
        BigDecimal premiumPrice,
        BigDecimal weekendMultiplier,
        ShowStatus status,
        int version) {
}
