package com.moviebooking.dto.booking;

import com.moviebooking.model.booking.Booking;
import com.moviebooking.model.payment.RefundReason;
import com.moviebooking.model.payment.RefundStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundResponse(Long bookingId, BigDecimal amount, int percentage, RefundReason reason,
                             RefundStatus status, String gatewayRef, Instant refundedAt) {

    public static RefundResponse from(Booking b) {
        return new RefundResponse(b.getId(), b.getRefundAmount(), b.getRefundPercent(), b.getRefundReason(),
                b.getRefundStatus(), b.getRefundGatewayRef(), b.getRefundedAt());
    }
}
