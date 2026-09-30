package com.moviebooking.service.payment;

import com.moviebooking.model.payment.Payment;
import com.moviebooking.service.payment.PaymentGateway.ChargeResult;
import com.moviebooking.service.payment.PaymentGateway.RefundResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Payment records plus gateway calls (the refund itself is recorded on the booking).
 * DB methods join the caller's transaction (MANDATORY); gateway methods must be called with
 * NO transaction open, so row locks are never held across a network call (LLD §6).
 */
public interface PaymentService {

    Optional<Payment> findByIdempotencyKey(String key);

    Payment startPayment(Long bookingId, BigDecimal amount, String idempotencyKey);

    /** Gateway call. No transaction may be open. */
    ChargeResult charge(Payment payment, String paymentToken);

    void markSucceeded(Long paymentId, String gatewayRef);

    void markFailed(Long paymentId, String reason);

    Optional<Payment> successfulPayment(Long bookingId);

    /** After a successful gateway refund: REFUNDED (full) or PARTIALLY_REFUNDED. */
    void markRefunded(Long paymentId, boolean fully);

    /** Gateway call. No transaction may be open. */
    RefundResult refundAtGateway(String gatewayRef, BigDecimal amount);
}
