package com.moviebooking.controller.booking;

import com.moviebooking.dto.booking.BookingResponse;
import com.moviebooking.dto.booking.ShowCancellationResult;
import com.moviebooking.service.booking.BookingQueryService;
import com.moviebooking.service.booking.BookingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminBookingController {

    private final BookingService bookingService;
    private final BookingQueryService queries;

    public AdminBookingController(BookingService bookingService, BookingQueryService queries) {
        this.bookingService = bookingService;
        this.queries = queries;
    }

    /** Cancels the show and refunds every booking 100%. Safe to re-run: finished bookings are skipped. */
    @PostMapping("/shows/{showId}/cancel")
    public ShowCancellationResult cancelShow(@PathVariable Long showId) {
        return bookingService.cancelShow(showId);
    }

    @GetMapping("/shows/{showId}/bookings")
    public List<BookingResponse> bookingsForShow(@PathVariable Long showId) {
        return queries.forShow(showId);
    }

    @GetMapping("/bookings/{bookingId}")
    public BookingResponse booking(@PathVariable Long bookingId) {
        return queries.getAsAdmin(bookingId);
    }
}
