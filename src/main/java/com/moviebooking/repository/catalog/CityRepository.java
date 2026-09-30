package com.moviebooking.repository.catalog;

import com.moviebooking.model.catalog.City;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CityRepository extends JpaRepository<City, Long> {

    List<City> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCaseAndStateIgnoreCase(String name, String state);
}
