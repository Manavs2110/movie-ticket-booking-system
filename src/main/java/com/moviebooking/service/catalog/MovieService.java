package com.moviebooking.service.catalog;

import com.moviebooking.dto.catalog.MovieRequest;
import com.moviebooking.dto.catalog.MovieResponse;
import com.moviebooking.model.catalog.Movie;

import java.util.List;

public interface MovieService {

    MovieResponse get(Long id);

    MovieResponse create(MovieRequest r);

    MovieResponse update(Long id, MovieRequest r);

    List<MovieResponse> listAll();


    Movie entity(Long id);
}
