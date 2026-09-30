package com.moviebooking.repository.payment;

import com.moviebooking.model.payment.Payment;
import com.moviebooking.model.payment.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findFirstByBookingIdAndStatusIn(Long bookingId, List<PaymentStatus> statuses);

    long countByBookingId(Long bookingId);
}
