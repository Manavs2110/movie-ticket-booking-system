package com.moviebooking.service.catalog.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.config.CacheNames;
import com.moviebooking.dto.catalog.TheaterRequest;
import com.moviebooking.dto.catalog.TheaterResponse;
import com.moviebooking.model.catalog.Theater;
import com.moviebooking.repository.catalog.TheaterRepository;
import com.moviebooking.service.catalog.CityService;
import com.moviebooking.service.catalog.TheaterService;
import com.moviebooking.service.refundpolicy.RefundPolicyService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TheaterServiceImpl implements TheaterService {

    private final TheaterRepository theaterRepository;
    private final CityService cityService;
    private final RefundPolicyService refundPolicyService;

    public TheaterServiceImpl(TheaterRepository theaterRepository, CityService cityService,
                          RefundPolicyService refundPolicyService) {
        this.theaterRepository = theaterRepository;
        this.cityService = cityService;
        this.refundPolicyService = refundPolicyService;
    }

    @Transactional(readOnly = true)
    public List<TheaterResponse> listByCity(Long cityId) {
        cityService.requireExists(cityId);
        return theaterRepository.findByCityIdOrderByNameAsc(cityId).stream().map(TheaterResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TheaterResponse get(Long id) {
        return TheaterResponse.from(entity(id));
    }

    @Transactional
    public TheaterResponse create(TheaterRequest r) {
        cityService.requireExists(r.cityId());
        validatePolicy(r.refundPolicyId());
        Theater theater = new Theater(r.cityId(), r.name().trim(), r.address().trim(), r.latitude(), r.longitude(),
                r.refundPolicyId());
        return TheaterResponse.from(theaterRepository.save(theater));
    }

    /** Name, location, active flag or refund policy can change what browse lists show. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.SHOWS_FOR_MOVIE, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.MOVIES_IN_CITY, allEntries = true)})
    @Transactional
    public TheaterResponse update(Long id, TheaterRequest r) {
        Theater theater = entity(id);
        if (!theater.getCityId().equals(r.cityId())) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "A theater cannot move to another city");
        }
        validatePolicy(r.refundPolicyId());
        theater.update(r.name().trim(), r.address().trim(), r.latitude(), r.longitude(), r.refundPolicyId(),
                r.active() == null || r.active());
        return TheaterResponse.from(theater);
    }

    @Transactional(readOnly = true)
    public Theater entity(Long id) {
        return theaterRepository.findById(id).orElseThrow(() -> AppException.notFound("Theater", id));
    }

    private void validatePolicy(Long policyId) {
        if (policyId != null) {
            refundPolicyService.requireExists(policyId);
        }
    }
}
