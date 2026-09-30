package com.moviebooking.repository.booking;

import com.moviebooking.model.booking.BookingItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BookingItemRepository extends JpaRepository<BookingItem, Long> {

    List<BookingItem> findByBookingIdOrderBySeatIdAsc(Long bookingId);

    List<BookingItem> findByBookingIdIn(Collection<Long> bookingIds);

    int countByBookingId(Long bookingId);
}
