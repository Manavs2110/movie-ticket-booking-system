package com.moviebooking.service.catalog;

import com.moviebooking.dto.catalog.CityRequest;
import com.moviebooking.dto.catalog.CityResponse;

import java.util.List;

public interface CityService {

    List<CityResponse> list();

    CityResponse create(CityRequest request);

    CityResponse update(Long id, CityRequest request);

    void requireExists(Long id);
}
