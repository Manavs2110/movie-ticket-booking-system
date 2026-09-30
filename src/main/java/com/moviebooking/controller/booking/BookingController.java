package com.moviebooking.controller.booking;

import com.moviebooking.common.CurrentUser;
import com.moviebooking.common.PageResponse;
import com.moviebooking.dto.booking.BookingResponse;
import com.moviebooking.dto.booking.HoldRequest;
import com.moviebooking.dto.booking.PayRequest;
import com.moviebooking.service.booking.BookingQueryService;
import com.moviebooking.service.booking.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BookingController {

    private final BookingService bookingService;
    private final BookingQueryService queries;

    public BookingController(BookingService bookingService, BookingQueryService queries) {
        this.bookingService = bookingService;
        this.queries = queries;
    }

    /** Hold 1-10 seats, all or nothing. 201 HELD, or 409 with the unavailable seat ids. */
    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse hold(@Valid @RequestBody HoldRequest request) {
        return bookingService.hold(CurrentUser.id(), request);
    }

    /** Replaying the same Idempotency-Key returns the same result and never charges twice. */
    @PostMapping("/bookings/{id}/pay")
    public BookingResponse pay(@PathVariable Long id,
                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                               @Valid @RequestBody(required = false) PayRequest request) {
        return bookingService.pay(CurrentUser.id(), id, idempotencyKey,
                request == null ? null : request.paymentToken());
    }

    @PostMapping("/bookings/{id}/cancel")
    public BookingResponse cancel(@PathVariable Long id) {
        return bookingService.cancel(CurrentUser.id(), id);
    }

    @GetMapping("/bookings/{id}")
    public BookingResponse get(@PathVariable Long id) {
        return queries.get(CurrentUser.id(), id);
    }

    @GetMapping("/me/bookings")
    public PageResponse<BookingResponse> history(@RequestParam(required = false) String cursor,
                                                 @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return queries.history(CurrentUser.id(), cursor, size);
    }
}
