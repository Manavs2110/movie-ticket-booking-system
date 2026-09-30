package com.moviebooking.dto.booking;

import com.moviebooking.model.catalog.SeatType;
import com.moviebooking.model.catalog.ShowStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * @param nextChangeAt earliest hold expiry among currently held seats: when the client should re-poll
 *                     (null when nothing is held)
 */
public record SeatMapResponse(Long showId, ShowStatus showStatus, boolean bookable, Instant nextChangeAt,
                              List<SeatView> seats) {

    public enum SeatState { AVAILABLE, HELD, BOOKED, BLOCKED }

    public record SeatView(Long seatId, String label, String row, int number, SeatType type, SeatState state,
                           BigDecimal price) {
    }
}
