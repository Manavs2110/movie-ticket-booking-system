package com.moviebooking.repository.pricing;

import com.moviebooking.model.pricing.DiscountCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiscountCodeRepository extends JpaRepository<DiscountCode, Long> {

    Optional<DiscountCode> findByCode(String code);

    boolean existsByCode(String code);
}
