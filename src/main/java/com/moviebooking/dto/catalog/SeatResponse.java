package com.moviebooking.dto.catalog;

import com.moviebooking.model.catalog.Seat;
import com.moviebooking.model.catalog.SeatType;

public record SeatResponse(Long id, String label, String rowLabel, int seatNumber, SeatType seatType, boolean active) {

    public static SeatResponse from(Seat s) {
        return new SeatResponse(s.getId(), s.label(), s.getRowLabel(), s.getSeatNumber(), s.getSeatType(), s.isActive());
    }
}
