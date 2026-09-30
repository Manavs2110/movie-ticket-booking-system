package com.moviebooking.dto.catalog;

import com.moviebooking.model.catalog.Screen;

public record ScreenResponse(Long id, Long theaterId, String name, int layoutVersion, long seatCount) {

    public static ScreenResponse from(Screen s, long seatCount) {
        return new ScreenResponse(s.getId(), s.getTheaterId(), s.getName(), s.getLayoutVersion(), seatCount);
    }
}
