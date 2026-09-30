package com.moviebooking.service.catalog.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.config.CacheNames;
import com.moviebooking.dto.catalog.MovieRequest;
import com.moviebooking.dto.catalog.MovieResponse;
import com.moviebooking.model.catalog.Movie;
import com.moviebooking.repository.catalog.MovieRepository;
import com.moviebooking.service.catalog.MovieService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MovieServiceImpl implements MovieService {

    private final MovieRepository movieRepository;

    public MovieServiceImpl(MovieRepository movieRepository) {
        this.movieRepository = movieRepository;
    }

    @Cacheable(cacheNames = CacheNames.MOVIE, key = "#id")
    @Transactional(readOnly = true)
    public MovieResponse get(Long id) {
        return MovieResponse.from(entity(id));
    }

    @Transactional
    public MovieResponse create(MovieRequest r) {
        Movie movie = new Movie(r.title().trim());
        apply(movie, r);
        return MovieResponse.from(movieRepository.save(movie));
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.MOVIE, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.MOVIES_IN_CITY, allEntries = true)})
    @Transactional
    public MovieResponse update(Long id, MovieRequest r) {
        Movie movie = entity(id);
        apply(movie, r);
        return MovieResponse.from(movie);
    }

    @Transactional(readOnly = true)
    public List<MovieResponse> listAll() {
        return movieRepository.findAll().stream().map(MovieResponse::from).toList();
    }


    @Transactional(readOnly = true)
    public Movie entity(Long id) {
        return movieRepository.findById(id).orElseThrow(() -> AppException.notFound("Movie", id));
    }

    private static void apply(Movie movie, MovieRequest r) {
        movie.update(r.title().trim(), r.description(), r.durationMinutes(), r.language().trim(),
                String.join(",", r.genres().stream().map(String::trim).toList()), r.certificate(), r.releaseDate(),
                r.cast() == null ? null : String.join(",", r.cast()), r.posterUrl(), r.trailerUrl());
    }
}
