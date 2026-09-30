package com.moviebooking.service.payment.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.model.payment.Payment;
import com.moviebooking.model.payment.PaymentStatus;
import com.moviebooking.repository.payment.PaymentRepository;
import com.moviebooking.service.payment.PaymentGateway.ChargeResult;
import com.moviebooking.service.payment.PaymentGateway.RefundResult;
import com.moviebooking.service.payment.PaymentGateway;
import com.moviebooking.service.payment.PaymentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Payment records plus gateway calls (the refund itself is recorded on the booking).
 * DB methods join the caller's transaction (MANDATORY); gateway methods must be called with
 * NO transaction open, so row locks are never held across a network call (LLD §6).
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway gateway;

    public PaymentServiceImpl(PaymentRepository paymentRepository, PaymentGateway gateway) {
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByIdempotencyKey(String key) {
        return paymentRepository.findByIdempotencyKey(key);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Payment startPayment(Long bookingId, BigDecimal amount, String idempotencyKey) {
        return paymentRepository.saveAndFlush(new Payment(bookingId, amount, idempotencyKey));
    }

    /** Gateway call. No transaction may be open. */
    public ChargeResult charge(Payment payment, String paymentToken) {
        return gateway.charge(payment.getAmount(), payment.getIdempotencyKey(), paymentToken);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markSucceeded(Long paymentId, String gatewayRef) {
        entity(paymentId).succeed(gatewayRef);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markFailed(Long paymentId, String reason) {
        entity(paymentId).fail(reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Payment> successfulPayment(Long bookingId) {
        return paymentRepository.findFirstByBookingIdAndStatusIn(bookingId, List.of(PaymentStatus.SUCCESS));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markRefunded(Long paymentId, boolean fully) {
        entity(paymentId).refunded(fully);
    }

    /** Gateway call. No transaction may be open. */
    public RefundResult refundAtGateway(String gatewayRef, BigDecimal amount) {
        return gateway.refund(gatewayRef, amount);
    }

    private Payment entity(Long id) {
        return paymentRepository.findById(id).orElseThrow(() -> AppException.notFound("Payment", id));
    }
}
