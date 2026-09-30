package com.moviebooking.service.pricing;

/**
 * How many times a customer has already used a discount code (bookings whose use was counted at payment).
 * Implemented by the booking module, so pricing never reads the booking table itself.
 */
public interface DiscountUsage {

    int redeemedBy(Long userId, Long discountCodeId);
}
