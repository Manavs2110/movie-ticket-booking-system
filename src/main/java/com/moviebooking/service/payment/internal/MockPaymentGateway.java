package com.moviebooking.service.payment.internal;

import com.moviebooking.service.payment.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for a payment provider.
 * <ul>
 *   <li>Token {@code tok_decline} → declined; anything else (or none) → approved.</li>
 *   <li>Same idempotency key → same result, never a second charge (like real providers).</li>
 *   <li>{@link #failNextRefunds(int)} lets tests exercise failed refunds.</li>
 * </ul>
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    public static final String DECLINE_TOKEN = "tok_decline";
    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    private final Map<String, ChargeResult> chargesByKey = new ConcurrentHashMap<>();
    private final AtomicInteger chargeCount = new AtomicInteger();
    private final AtomicInteger refundFailuresToInject = new AtomicInteger();

    @Override
    public ChargeResult charge(BigDecimal amount, String idempotencyKey, String paymentToken) {
        return chargesByKey.computeIfAbsent(idempotencyKey, key -> {
            if (DECLINE_TOKEN.equals(paymentToken)) {
                log.info("[mock-gateway] charge {} declined (key {})", amount, key);
                return ChargeResult.declined("Card declined");
            }
            chargeCount.incrementAndGet();
            String ref = "pay_" + UUID.randomUUID();
            log.info("[mock-gateway] charged {} → {} (key {})", amount, ref, key);
            return ChargeResult.approved(ref);
        });
    }

    @Override
    public RefundResult refund(String gatewayRef, BigDecimal amount) {
        if (refundFailuresToInject.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            log.info("[mock-gateway] refund {} of {} FAILED (injected)", amount, gatewayRef);
            return new RefundResult(false, null, "Provider unavailable");
        }
        String ref = "rfnd_" + UUID.randomUUID();
        log.info("[mock-gateway] refunded {} of {} → {}", amount, gatewayRef, ref);
        return new RefundResult(true, ref, null);
    }

    /** Number of successful charges actually made (tests assert idempotency with it). */
    public int successfulCharges() {
        return chargeCount.get();
    }

    public void failNextRefunds(int n) {
        refundFailuresToInject.set(n);
    }
}
