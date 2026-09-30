package com.moviebooking.repository.booking;

import com.moviebooking.model.booking.Booking;
import com.moviebooking.model.booking.BookingStatus;
import com.moviebooking.model.payment.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** Ownership is part of the lookup: someone else's booking is simply "not found" (404). */
    Optional<Booking> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id and b.userId = :userId")
    Optional<Booking> lockByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from Booking b
            where b.userId = :userId and b.showId = :showId
              and b.status = com.moviebooking.model.booking.BookingStatus.HELD and b.holdExpiresAt <= :now
            """)
    List<Booking> lockStaleHolds(@Param("userId") Long userId, @Param("showId") Long showId, @Param("now") Instant now);

    @Query("""
            select count(b) > 0 from Booking b
            where b.userId = :userId and b.showId = :showId
              and (b.status = com.moviebooking.model.booking.BookingStatus.PAYMENT_PENDING
                   or (b.status = com.moviebooking.model.booking.BookingStatus.HELD and b.holdExpiresAt > :now))
            """)
    boolean existsActiveHold(@Param("userId") Long userId, @Param("showId") Long showId, @Param("now") Instant now);

    @Query("select b.id from Booking b where b.showId = :showId and b.status in :statuses order by b.id")
    List<Long> findIdsByShowIdAndStatusIn(@Param("showId") Long showId,
                                          @Param("statuses") Collection<BookingStatus> statuses);

    List<Booking> findByShowIdOrderByIdAsc(Long showId);

    boolean existsByShowId(Long showId);

    List<Booking> findByRefundStatusNotNullOrderByIdDesc();

    List<Booking> findByRefundStatusOrderByIdDesc(RefundStatus status);

    @Query("select b from Booking b where b.userId = :userId order by b.createdAt desc, b.id desc")
    List<Booking> firstHistoryPage(@Param("userId") Long userId, Pageable pageable);

    @Query("""
            select b from Booking b
            where b.userId = :userId
              and (b.createdAt < :cursorAt or (b.createdAt = :cursorAt and b.id < :cursorId))
            order by b.createdAt desc, b.id desc
            """)
    List<Booking> historyPageAfter(@Param("userId") Long userId, @Param("cursorAt") Instant cursorAt,
                                   @Param("cursorId") Long cursorId, Pageable pageable);
}
