package com.moviebooking.repository.catalog;

import com.moviebooking.model.catalog.Theater;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TheaterRepository extends JpaRepository<Theater, Long> {

    List<Theater> findByCityIdOrderByNameAsc(Long cityId);
}
