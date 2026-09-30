package com.moviebooking.service.pricing;

import com.moviebooking.model.catalog.Seat;
import com.moviebooking.model.catalog.SeatType;
import com.moviebooking.model.catalog.ShowDetails;
import com.moviebooking.model.pricing.PricedSeat;

import java.math.BigDecimal;
import java.util.List;

/**
 * seat price = base × seat-type multiplier × (weekend ? weekend multiplier : 1), rounded HALF_UP (HLD §6.3).
 * Weekend is a day-based multiplier, not a seat type, so "premium on Saturday" exists naturally.
 */
public interface PricingService {

    List<PricedSeat> priceSeats(ShowDetails show, List<Seat> seats);

    /** (PREMIUM ? premium price : regular price) × (show starts Sat/Sun in IST ? weekend multiplier : 1). */
    BigDecimal seatPrice(ShowDetails show, SeatType type);
}
