package com.moviebooking.service.notification;

import com.moviebooking.model.notification.EventType;
import com.moviebooking.model.notification.OutboxEvent;
import com.moviebooking.model.notification.OutboxStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Writes events into {@code outbox_event} (LLD §10.1). The write methods can only run inside the caller's
 * business transaction (MANDATORY): if the booking commits, its event exists; if it rolls back, it never did.
 * The table is also the event audit log: rows are kept, with {@code sent_at} (Kafka acknowledged) and
 * {@code processed_at} (consumer finished).
 */
public interface OutboxService {

    /** An event due now. */
    void publish(EventType type, Long aggregateId, Map<String, Object> payload);

    /** An event due later: the publisher picks it up once {@code dueAt} has passed (used for show reminders). */
    void schedule(EventType type, Long aggregateId, Map<String, Object> payload, Instant dueAt);

    /** Cancels not-yet-sent events, e.g. the reminder of a booking that was just cancelled. */
    int cancelPending(Long aggregateId, EventType type);

    /** Consumer dedupe: marks the event PROCESSED; false if it already was (duplicate) or was cancelled. */
    boolean markProcessed(long eventId);

    List<OutboxEvent> list(OutboxStatus status, Long aggregateId, int limit);
}
