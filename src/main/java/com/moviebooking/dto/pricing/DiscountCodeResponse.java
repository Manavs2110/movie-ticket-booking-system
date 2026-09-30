package com.moviebooking.dto.pricing;

import com.moviebooking.model.pricing.DiscountCode;
import com.moviebooking.model.pricing.DiscountType;

import java.math.BigDecimal;
import java.time.Instant;

public record DiscountCodeResponse(Long id, String code, DiscountType type, BigDecimal value, BigDecimal maxDiscount,
                                   BigDecimal minOrder, Instant validFrom, Instant validTo, Integer usageLimit,
                                   int usedCount, int perUserLimit, boolean active) {

    public static DiscountCodeResponse from(DiscountCode d) {
        return new DiscountCodeResponse(d.getId(), d.getCode(), d.getType(), d.getValue(), d.getMaxDiscount(),
                d.getMinOrder(), d.getValidFrom(), d.getValidTo(), d.getUsageLimit(), d.getUsedCount(),
                d.getPerUserLimit(), d.isActive());
    }
}
