package com.moviebooking.dto.booking;

import java.util.List;

public record ShowCancellationResult(Long showId, boolean newlyCancelled, int bookingsProcessed,
                                     List<Long> failedBookingIds) {
}
