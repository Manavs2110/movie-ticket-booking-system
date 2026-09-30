package com.moviebooking.dto.catalog;

import com.moviebooking.model.catalog.City;

public record CityResponse(Long id, String name, String state) {

    public static CityResponse from(City c) {
        return new CityResponse(c.getId(), c.getName(), c.getState());
    }
}
