package com.moviebooking.dto.catalog;

import com.moviebooking.model.catalog.SeatType;

/** Both optional: change a seat's type and/or mark it broken (active=false). */
public record SeatUpdateRequest(SeatType seatType, Boolean active) {
}
