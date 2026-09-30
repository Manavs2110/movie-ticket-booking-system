package com.moviebooking.model.pricing;

import com.moviebooking.model.catalog.SeatType;

import java.math.BigDecimal;

public record PricedSeat(Long seatId, String label, SeatType seatType, BigDecimal price) {
}
