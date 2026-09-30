package com.moviebooking.service.pricing.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.dto.pricing.PriceBreakdown;
import com.moviebooking.model.catalog.Seat;
import com.moviebooking.model.catalog.ShowDetails;
import com.moviebooking.model.pricing.DiscountQuote;
import com.moviebooking.model.pricing.PricedSeat;
import com.moviebooking.service.catalog.ScreenService;
import com.moviebooking.service.catalog.ShowService;
import com.moviebooking.service.pricing.CheckoutPricing;
import com.moviebooking.service.pricing.DiscountService;
import com.moviebooking.service.pricing.PricingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

/** Builds the price breakdown for a set of seats: used by the hold and by the discount preview. */
@Service
public class CheckoutPricingImpl implements CheckoutPricing {

    private final ShowService showService;
    private final ScreenService screenService;
    private final PricingService pricingService;
    private final DiscountService discountService;

    public CheckoutPricingImpl(ShowService showService, ScreenService screenService, PricingService pricingService,
                           DiscountService discountService) {
        this.showService = showService;
        this.screenService = screenService;
        this.pricingService = pricingService;
        this.discountService = discountService;
    }

    /** Preview only (POST /discounts/preview): validates the code, counts nothing, holds nothing. */
    @Transactional(readOnly = true)
    public PriceBreakdown preview(Long showId, List<Long> seatIds, String code, Long userId) {
        ShowDetails show = showService.details(showId);
        return price(show, validSeats(show, seatIds), code, userId);
    }

    public PriceBreakdown price(ShowDetails show, List<Seat> seats, String code, Long userId) {
        List<PricedSeat> priced = pricingService.priceSeats(show, seats);
        BigDecimal subtotal = priced.stream().map(PricedSeat::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (code == null || code.isBlank()) {
            return new PriceBreakdown(priced, subtotal, null, null, BigDecimal.ZERO.setScale(2), subtotal);
        }
        DiscountQuote quote = discountService.preview(code, userId, subtotal);
        return new PriceBreakdown(priced, subtotal, quote.code(), quote.codeId(), quote.amount(),
                subtotal.subtract(quote.amount()));
    }

    /** Seats must be distinct, belong to the show's screen and be active; otherwise 400 INVALID_SEATS. */
    public List<Seat> validSeats(ShowDetails show, List<Long> seatIds) {
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new AppException(ErrorCode.INVALID_SEATS, "Duplicate seat ids");
        }
        List<Seat> seats = screenService.activeSeats(show.screenId(), seatIds);
        if (seats.size() != seatIds.size()) {
            throw new AppException(ErrorCode.INVALID_SEATS,
                    "Some seats don't belong to this show's screen or are out of service");
        }
        return seats.stream().sorted((a, b) -> a.getId().compareTo(b.getId())).toList();
    }
}
