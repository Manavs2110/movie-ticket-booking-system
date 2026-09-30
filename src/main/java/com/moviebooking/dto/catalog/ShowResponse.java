package com.moviebooking.dto.catalog;

import com.moviebooking.model.catalog.ShowDetails;
import com.moviebooking.model.catalog.ShowStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ShowResponse(Long id, Long movieId, String movieTitle, Long theaterId, String theaterName,
                           Long cityId, Long screenId, String screenName, Instant startTime, Instant endTime,
                           BigDecimal regularPrice, BigDecimal premiumPrice,
                           BigDecimal weekendMultiplier, ShowStatus status) {

    public static ShowResponse from(ShowDetails d) {
        return new ShowResponse(d.id(), d.movieId(), d.movieTitle(), d.theaterId(), d.theaterName(), d.cityId(),
                d.screenId(), d.screenName(), d.startTime(), d.endTime(), d.regularPrice(), d.premiumPrice(),
                d.weekendMultiplier(), d.status());
    }
}
