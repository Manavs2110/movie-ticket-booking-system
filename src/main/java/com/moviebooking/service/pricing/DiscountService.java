package com.moviebooking.service.pricing;

import com.moviebooking.dto.pricing.DiscountCodeRequest;
import com.moviebooking.dto.pricing.DiscountCodeResponse;
import com.moviebooking.model.pricing.DiscountQuote;

import java.math.BigDecimal;
import java.util.List;

/**
 * Discount codes (LLD §7.2).
 * <ul>
 *   <li>{@code #preview}: validated at hold time, nothing counted.</li>
 *   <li>{@code #redeem}: counted atomically at pay time, before the charge.</li>
 *   <li>{@code #giveBack}: undone if the charge is declined or the booking fails to confirm.</li>
 * </ul>
 */
public interface DiscountService {

    DiscountQuote preview(String rawCode, Long userId, BigDecimal subtotal);

    /**
     * Counts one use. The conditional UPDATE locks the code's row, so the per-user check that follows runs
     * one-at-a-time for that code. Must run inside the caller's payment transaction; the caller then flags the
     * booking ({@code booking.discount_redeemed}), which is what the per-user limit counts.
     */
    void redeem(Long codeId, Long userId);

    /** Gives one use back (declined card, failed confirm). The caller clears the booking's flag. */
    void giveBack(Long codeId);

    String codeOf(Long id);

    List<DiscountCodeResponse> list();

    DiscountCodeResponse get(Long id);

    DiscountCodeResponse create(DiscountCodeRequest r);

    DiscountCodeResponse update(Long id, DiscountCodeRequest r);

    /** Codes are deactivated, never deleted: bookings reference them. */
    void deactivate(Long id);
}
