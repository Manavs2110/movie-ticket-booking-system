package com.moviebooking.service.payment;

import java.math.BigDecimal;

/** Swap point for a real provider (HLD §11). Implementations must be idempotent per idempotency key. */
public interface PaymentGateway {

    /**
     * @param paymentToken the client-side card/UPI token; the mock declines {@code tok_decline}
     */
    ChargeResult charge(BigDecimal amount, String idempotencyKey, String paymentToken);

    RefundResult refund(String gatewayRef, BigDecimal amount);

    record ChargeResult(boolean approved, String gatewayRef, String failureReason) {
        public static ChargeResult approved(String ref) {
            return new ChargeResult(true, ref, null);
        }

        public static ChargeResult declined(String reason) {
            return new ChargeResult(false, null, reason);
        }
    }

    record RefundResult(boolean success, String gatewayRef, String failureReason) {
    }
}
