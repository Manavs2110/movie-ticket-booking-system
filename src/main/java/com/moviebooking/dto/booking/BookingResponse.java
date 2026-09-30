package com.moviebooking.dto.booking;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moviebooking.model.booking.BookingStatus;
import com.moviebooking.model.catalog.SeatType;
import com.moviebooking.model.payment.RefundReason;
import com.moviebooking.model.payment.RefundStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingResponse(
        Long bookingId,
        BookingStatus status,
        Long showId,
        String movieTitle,
        String theaterName,
        String screenName,
        Instant showStartTime,
        Instant holdExpiresAt,
        List<Item> items,
        BigDecimal subtotal,
        String discountCode,
        BigDecimal discount,
        BigDecimal total,
        RefundInfo refund,
        Instant createdAt) {

    public record Item(Long seatId, String seat, SeatType type, BigDecimal price) {
    }

    public record RefundInfo(BigDecimal amount, int percentage, RefundReason reason, RefundStatus status) {
    }
}
