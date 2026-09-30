package com.moviebooking.common;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class SeatsUnavailableException extends AppException {

    public SeatsUnavailableException(Collection<Long> seatIds) {
        super(ErrorCode.SEATS_UNAVAILABLE, "Some seats are no longer available",
                Map.of("unavailableSeatIds", List.copyOf(seatIds).stream().sorted().toList()));
    }
}
