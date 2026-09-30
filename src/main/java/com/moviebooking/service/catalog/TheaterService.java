package com.moviebooking.service.catalog;

import com.moviebooking.dto.catalog.TheaterRequest;
import com.moviebooking.dto.catalog.TheaterResponse;
import com.moviebooking.model.catalog.Theater;

import java.util.List;

public interface TheaterService {

    List<TheaterResponse> listByCity(Long cityId);

    TheaterResponse get(Long id);

    TheaterResponse create(TheaterRequest r);

    TheaterResponse update(Long id, TheaterRequest r);

    Theater entity(Long id);
}
