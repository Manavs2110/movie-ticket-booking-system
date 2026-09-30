package com.moviebooking.service.pricing;

import com.moviebooking.dto.pricing.PriceBreakdown;
import com.moviebooking.model.catalog.Seat;
import com.moviebooking.model.catalog.ShowDetails;

import java.util.List;

/** Builds the price breakdown for a set of seats: used by the hold and by the discount preview. */
public interface CheckoutPricing {

    /** Preview only (POST /discounts/preview): validates the code, counts nothing, holds nothing. */
    PriceBreakdown preview(Long showId, List<Long> seatIds, String code, Long userId);

    PriceBreakdown price(ShowDetails show, List<Seat> seats, String code, Long userId);

    /** Seats must be distinct, belong to the show's screen and be active; otherwise 400 INVALID_SEATS. */
    List<Seat> validSeats(ShowDetails show, List<Long> seatIds);
}
