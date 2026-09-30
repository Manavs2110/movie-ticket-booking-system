package com.moviebooking.service.catalog;

import com.moviebooking.dto.catalog.AddSeatRowsRequest;
import com.moviebooking.dto.catalog.ScreenRequest;
import com.moviebooking.dto.catalog.ScreenResponse;
import com.moviebooking.dto.catalog.SeatResponse;
import com.moviebooking.dto.catalog.SeatUpdateRequest;
import com.moviebooking.model.catalog.Screen;
import com.moviebooking.model.catalog.Seat;
import com.moviebooking.model.catalog.SeatLayout;

import java.util.Collection;
import java.util.List;

/**
 * Screens and seat layouts (LLD §7.5).
 * Seat type / active changes are allowed any time and bump the layout version (new cache key, new ETag).
 * Adding seats is blocked while future shows on the screen have held or sold seats.
 */
public interface ScreenService {

    ScreenResponse create(Long theaterId, ScreenRequest request);

    List<ScreenResponse> listByTheater(Long theaterId);

    List<SeatResponse> seats(Long screenId);

    List<SeatResponse> addRows(Long screenId, AddSeatRowsRequest request);

    /** Type and active flag may change any time; existing bookings keep their snapshot price. */
    SeatResponse updateSeat(Long screenId, Long seatId, SeatUpdateRequest request);

    /** Layout at a specific version; a layout change means a new version and therefore a new cache key. */
    SeatLayout layout(Long screenId, int layoutVersion);

    /** Active seats of the screen among the given ids (for hold validation; never cached). */
    List<Seat> activeSeats(Long screenId, Collection<Long> seatIds);

    Screen entity(Long id);
}
