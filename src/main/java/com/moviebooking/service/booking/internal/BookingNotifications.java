package com.moviebooking.service.booking.internal;

import com.moviebooking.config.AppProperties;
import com.moviebooking.model.booking.Booking;
import com.moviebooking.model.booking.BookingItem;
import com.moviebooking.model.catalog.ShowDetails;
import com.moviebooking.model.notification.EventType;
import com.moviebooking.repository.booking.BookingItemRepository;
import com.moviebooking.service.auth.AppUserDetailsService.UserContact;
import com.moviebooking.service.auth.AppUserDetailsService;
import com.moviebooking.service.notification.OutboxService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes booking events to the outbox, inside the caller's transaction. The payload carries everything
 * needed to render the message, so the relay never has to query business tables.
 */
@Component
class BookingNotifications {

    private final OutboxService outbox;
    private final AppUserDetailsService users;
    private final BookingItemRepository items;
    private final ZoneId zone;

    BookingNotifications(OutboxService outbox, AppUserDetailsService users, BookingItemRepository items,
                         AppProperties props) {
        this.outbox = outbox;
        this.users = users;
        this.items = items;
        this.zone = props.zoneId();
    }

    void publish(EventType type, Booking booking, ShowDetails show) {
        publish(type, booking, show, Map.of());
    }

    /** A reminder row due at {@code dueAt}; the outbox publisher sends it when the time comes. */
    void scheduleReminder(Booking booking, ShowDetails show, Instant dueAt) {
        outbox.schedule(EventType.SHOW_REMINDER, booking.getId(), payload(booking, show, Map.of()), dueAt);
    }

    void cancelReminder(Booking booking) {
        outbox.cancelPending(booking.getId(), EventType.SHOW_REMINDER);
    }

    void publish(EventType type, Booking booking, ShowDetails show, Map<String, Object> extra) {
        outbox.publish(type, booking.getId(), payload(booking, show, extra));
    }

    private Map<String, Object> payload(Booking booking, ShowDetails show, Map<String, Object> extra) {
        UserContact user = users.contact(booking.getUserId());
        List<String> seats = items.findByBookingIdOrderBySeatIdAsc(booking.getId()).stream()
                .map(BookingItem::getSeatLabel).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("email", user.email());
        payload.put("name", user.fullName());
        payload.put("movie", show.movieTitle());
        payload.put("theater", show.theaterName());
        payload.put("screen", show.screenName());
        payload.put("showTime", show.startTime().atZone(zone).toOffsetDateTime().toString());
        payload.put("seats", seats);
        payload.put("total", booking.getTotalAmount());
        payload.putAll(extra);
        return payload;
    }

    static Map<String, Object> refundExtra(BigDecimal amount, int percent) {
        return Map.of("refundAmount", amount, "refundPercent", percent);
    }
}
