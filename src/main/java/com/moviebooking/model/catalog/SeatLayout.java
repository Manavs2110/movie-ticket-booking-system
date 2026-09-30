package com.moviebooking.model.catalog;

import java.util.List;

/** Cached seat layout of a screen at a given layout version (cache key screenId:layoutVersion). */
public record SeatLayout(Long screenId, int layoutVersion, List<LayoutSeat> seats) {

    public record LayoutSeat(Long id, String rowLabel, int seatNumber, String label, SeatType seatType,
                             boolean active) {
    }
}
