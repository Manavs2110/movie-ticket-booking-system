package com.moviebooking.service.booking;

import com.moviebooking.common.PageResponse;
import com.moviebooking.dto.booking.BookingResponse;
import com.moviebooking.dto.booking.RefundResponse;
import com.moviebooking.model.payment.RefundStatus;

import java.util.List;

/** Read side of bookings: single booking, history (cursor pagination), admin listing. */
public interface BookingQueryService {

    /** Someone else's booking is reported as not found, so booking ids can't be probed. */
    BookingResponse get(Long userId, Long bookingId);

    BookingResponse getAsAdmin(Long bookingId);

    /** Newest first, keyset on (created_at, id): stays fast however deep the user pages. */
    PageResponse<BookingResponse> history(Long userId, String cursor, int size);

    /** Used by the notification consumer before sending a reminder that may have been queued earlier. */
    boolean isStillConfirmedAndScheduled(Long bookingId);

    List<BookingResponse> forShow(Long showId);

    /** Admin: bookings with a refund, newest first; {@code status} narrows it (e.g. FAILED). */
    List<RefundResponse> refunds(RefundStatus status);
}
