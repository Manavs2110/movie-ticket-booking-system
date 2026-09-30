package com.moviebooking.repository.catalog;

import com.moviebooking.model.catalog.Show;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ShowRepository extends JpaRepository<Show, Long> {

    /** Friendly pre-check; the ex_show_no_overlap exclusion constraint is the real guard. */
    @Query("""
            select count(s) > 0 from Show s
            where s.screenId = :screenId
              and s.status = com.moviebooking.model.catalog.ShowStatus.SCHEDULED
              and s.startTime < :end and s.endTime > :start
              and s.id <> :excludeId
            """)
    boolean existsOverlap(@Param("screenId") Long screenId, @Param("start") Instant start,
                          @Param("end") Instant end, @Param("excludeId") Long excludeId);

    @Query("""
            select s.id from Show s
            where s.screenId = :screenId
              and s.status = com.moviebooking.model.catalog.ShowStatus.SCHEDULED
              and s.startTime > :now
            """)
    List<Long> findFutureScheduledIds(@Param("screenId") Long screenId, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Show s where s.id = :id")
    Optional<Show> lockById(@Param("id") Long id);

    /** FOR SHARE: many holds can proceed together, but an admin cancelling the show waits for them. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from Show s where s.id = :id")
    Optional<Show> lockSharedById(@Param("id") Long id);
}
