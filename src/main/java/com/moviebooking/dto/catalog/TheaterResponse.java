package com.moviebooking.dto.catalog;

import com.moviebooking.model.catalog.Theater;

import java.math.BigDecimal;

public record TheaterResponse(Long id, Long cityId, String name, String address, BigDecimal latitude,
                              BigDecimal longitude, Long refundPolicyId, boolean active) {

    public static TheaterResponse from(Theater t) {
        return new TheaterResponse(t.getId(), t.getCityId(), t.getName(), t.getAddress(), t.getLatitude(),
                t.getLongitude(), t.getRefundPolicyId(), t.isActive());
    }
}
