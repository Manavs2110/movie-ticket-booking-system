package com.moviebooking.dto.pricing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.moviebooking.model.pricing.PricedSeat;

import java.math.BigDecimal;
import java.util.List;

public record PriceBreakdown(List<PricedSeat> items, BigDecimal subtotal, String discountCode,
                             @JsonIgnore Long discountCodeId, BigDecimal discount, BigDecimal total) {
}
