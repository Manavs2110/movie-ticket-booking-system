package com.moviebooking.repository.catalog;

import com.moviebooking.model.catalog.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByScreenIdOrderByRowLabelAscSeatNumberAsc(Long screenId);

    List<Seat> findByScreenIdAndIdIn(Long screenId, Collection<Long> ids);

    boolean existsByScreenIdAndRowLabel(Long screenId, String rowLabel);

    long countByScreenId(Long screenId);
}
