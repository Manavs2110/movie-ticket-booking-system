package com.moviebooking.controller.booking;

import com.moviebooking.dto.booking.RefundResponse;
import com.moviebooking.model.payment.RefundStatus;
import com.moviebooking.service.booking.BookingQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/refunds")
public class AdminRefundController {

    private final BookingQueryService bookings;

    public AdminRefundController(BookingQueryService bookings) {
        this.bookings = bookings;
    }

    /** e.g. {@code ?status=FAILED} to find refunds that need attention. */
    @GetMapping
    public List<RefundResponse> refunds(@RequestParam(required = false) RefundStatus status) {
        return bookings.refunds(status);
    }
}
