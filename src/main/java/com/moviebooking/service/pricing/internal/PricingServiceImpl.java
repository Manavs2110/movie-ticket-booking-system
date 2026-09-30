package com.moviebooking.service.pricing.internal;

import com.moviebooking.config.AppProperties;
import com.moviebooking.model.catalog.Seat;
import com.moviebooking.model.catalog.SeatType;
import com.moviebooking.model.catalog.ShowDetails;
import com.moviebooking.model.pricing.PricedSeat;
import com.moviebooking.service.pricing.PricingService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * seat price = base × seat-type multiplier × (weekend ? weekend multiplier : 1), rounded HALF_UP (HLD §6.3).
 * Weekend is a day-based multiplier, not a seat type, so "premium on Saturday" exists naturally.
 */
@Service
public class PricingServiceImpl implements PricingService {

    private final ZoneId zone;

    public PricingServiceImpl(AppProperties props) {
        this.zone = props.zoneId();
    }

    @Override
    public List<PricedSeat> priceSeats(ShowDetails show, List<Seat> seats) {
        return seats.stream()
                .map(s -> new PricedSeat(s.getId(), s.label(), s.getSeatType(), seatPrice(show, s.getSeatType())))
                .toList();
    }

    @Override
    public BigDecimal seatPrice(ShowDetails show, SeatType type) {
        return seatPrice(show.regularPrice(), show.premiumPrice(), show.weekendMultiplier(), show.startTime(), type,
                zone);
    }

    /** Pure rule, kept static so it can be unit-tested without a show. Rounded to 2 decimals, HALF_UP. */
    static BigDecimal seatPrice(BigDecimal regularPrice, BigDecimal premiumPrice, BigDecimal weekendMultiplier,
                                Instant startTime, SeatType type, ZoneId zone) {
        BigDecimal seatTypePrice = type == SeatType.PREMIUM ? premiumPrice : regularPrice;
        DayOfWeek day = startTime.atZone(zone).getDayOfWeek();
        BigDecimal weekend = (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) ? weekendMultiplier : BigDecimal.ONE;
        return seatTypePrice.multiply(weekend).setScale(2, RoundingMode.HALF_UP);
    }
}
