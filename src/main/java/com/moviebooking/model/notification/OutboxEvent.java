package com.moviebooking.model.notification;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;

/** One message waiting to be (or already) sent: a row of outbox_event. */
public record OutboxEvent(
        Long id,
        EventType eventType,
        Long aggregateId,
        @JsonRawValue String payload,
        OutboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant sentAt,
        Instant processedAt) {
}
