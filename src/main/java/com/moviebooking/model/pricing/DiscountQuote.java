package com.moviebooking.model.pricing;

import java.math.BigDecimal;

/** Result of validating a code against a subtotal: nothing is counted yet. */
public record DiscountQuote(Long codeId, String code, BigDecimal amount) {
}
