package com.moviebooking.service.notification.internal;

import com.moviebooking.dto.notification.BookingEventMessage;
import com.moviebooking.service.notification.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Mock channel: renders the message and logs it. */
@Component
public class LogNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationSender.class);

    @Override
    public void send(BookingEventMessage e) {
        log.info("[notify] event={} to={} | {}", e.eventId(), e.field("email"), render(e));
    }

    static String render(BookingEventMessage e) {
        String movie = e.field("movie");
        String when = e.field("showTime");
        String theater = e.field("theater");
        return switch (e.type()) {
            case BOOKING_CONFIRMED -> "Booking #%s confirmed: %s at %s, %s, seats %s, total %s"
                    .formatted(e.aggregateId(), movie, theater, when, e.field("seats"), e.field("total"));
            case BOOKING_CANCELLED -> "Booking #%s for %s (%s) was cancelled".formatted(e.aggregateId(), movie, when);
            case REFUND_PROCESSED -> "Refund of %s processed for booking #%s".formatted(e.field("refundAmount"),
                    e.aggregateId());
            case SHOW_CANCELLED -> "%s at %s on %s was cancelled by the theater; you'll get a full refund"
                    .formatted(movie, theater, when);
            case SHOW_REMINDER -> "Reminder: %s at %s starts at %s, seats %s".formatted(movie, theater, when,
                    e.field("seats"));
        };
    }
}
