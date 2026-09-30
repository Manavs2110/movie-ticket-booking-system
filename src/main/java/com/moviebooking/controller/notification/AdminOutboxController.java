package com.moviebooking.controller.notification;

import com.moviebooking.model.notification.OutboxEvent;
import com.moviebooking.model.notification.OutboxStatus;
import com.moviebooking.service.notification.OutboxService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/outbox")
public class AdminOutboxController {

    private final OutboxService outboxService;

    public AdminOutboxController(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    /** e.g. {@code ?status=FAILED} to see undelivered notifications, or {@code ?bookingId=} for one booking. */
    @GetMapping
    public List<OutboxEvent> events(@RequestParam(required = false) OutboxStatus status,
                                    @RequestParam(required = false) Long bookingId,
                                    @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return outboxService.list(status, bookingId, limit);
    }
}
