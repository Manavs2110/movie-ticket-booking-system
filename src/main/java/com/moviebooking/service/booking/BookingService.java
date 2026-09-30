package com.moviebooking.service.booking;

import com.moviebooking.dto.booking.BookingResponse;
import com.moviebooking.dto.booking.HoldRequest;
import com.moviebooking.dto.booking.ShowCancellationResult;

/**
 * Booking lifecycle: lock → pay → confirm, and cancellation (LLD §6).
 * <p>
 * Transactions are opened explicitly with {@code TransactionTemplate} so they stay short, and the payment
 * gateway is always called with <b>no</b> transaction open: row locks are never held across a network call.
 * Seat locking goes through {@code SeatHoldService}; this class doesn't know it has a Redis and a Postgres layer.
 */
public interface BookingService {

    BookingResponse hold(Long userId, HoldRequest request);

    BookingResponse pay(Long userId, Long bookingId, String idempotencyKey, String paymentToken);

    BookingResponse cancel(Long userId, Long bookingId);

    ShowCancellationResult cancelShow(Long showId);

    void cancelForShow(Long bookingId);
}
