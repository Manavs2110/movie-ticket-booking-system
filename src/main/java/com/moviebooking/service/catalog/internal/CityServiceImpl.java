package com.moviebooking.service.catalog.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.config.CacheNames;
import com.moviebooking.dto.catalog.CityRequest;
import com.moviebooking.dto.catalog.CityResponse;
import com.moviebooking.model.catalog.City;
import com.moviebooking.repository.catalog.CityRepository;
import com.moviebooking.service.catalog.CityService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CityServiceImpl implements CityService {

    private final CityRepository cityRepository;

    public CityServiceImpl(CityRepository cityRepository) {
        this.cityRepository = cityRepository;
    }

    @Cacheable(cacheNames = CacheNames.CITIES, key = "'all'")
    @Transactional(readOnly = true)
    public List<CityResponse> list() {
        return cityRepository.findAllByOrderByNameAsc().stream().map(CityResponse::from).toList();
    }

    @CacheEvict(cacheNames = CacheNames.CITIES, key = "'all'")
    @Transactional
    public CityResponse create(CityRequest request) {
        if (cityRepository.existsByNameIgnoreCaseAndStateIgnoreCase(request.name().trim(), request.state().trim())) {
            throw new AppException(ErrorCode.CONFLICT, "City already exists");
        }
        return CityResponse.from(cityRepository.save(new City(request.name().trim(), request.state().trim())));
    }

    @CacheEvict(cacheNames = CacheNames.CITIES, key = "'all'")
    @Transactional
    public CityResponse update(Long id, CityRequest request) {
        City city = cityRepository.findById(id).orElseThrow(() -> AppException.notFound("City", id));
        city.update(request.name().trim(), request.state().trim());
        return CityResponse.from(city);
    }

    @Transactional(readOnly = true)
    public void requireExists(Long id) {
        if (!cityRepository.existsById(id)) {
            throw AppException.notFound("City", id);
        }
    }
}
