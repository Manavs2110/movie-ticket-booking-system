package com.moviebooking.repository.catalog;

import com.moviebooking.model.catalog.Screen;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScreenRepository extends JpaRepository<Screen, Long> {

    List<Screen> findByTheaterIdOrderByNameAsc(Long theaterId);

    boolean existsByTheaterIdAndNameIgnoreCase(Long theaterId, String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Screen s where s.id = :id")
    Optional<Screen> lockById(@Param("id") Long id);
}
