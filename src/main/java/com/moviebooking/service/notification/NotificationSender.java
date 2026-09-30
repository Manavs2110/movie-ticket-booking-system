package com.moviebooking.service.notification;

import com.moviebooking.dto.notification.BookingEventMessage;

/**
 * Delivers one event to the customer (email / SMS / push). Throw to signal failure; the consumer then retries
 * through the retry topics. Implementations should pass {@code eventId} to the provider as its idempotency key,
 * because delivery is at least once.
 */
public interface NotificationSender {

    void send(BookingEventMessage event);
}
