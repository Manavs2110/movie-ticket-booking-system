package com.moviebooking.dto.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.moviebooking.model.notification.EventType;

import java.time.Instant;

/**
 * The Kafka message on {@code booking-events} (LLD §10.3). {@code eventId} is the outbox row id: the key for
 * consumer-side dedupe and the idempotency key passed to the email/SMS provider. The Kafka record key is the
 * booking id, so all events of one booking stay in order.
 */
public record BookingEventMessage(long eventId, EventType type, long aggregateId, Instant occurredAt,
                                  JsonNode payload) {

    public String field(String name) {
        JsonNode node = payload == null ? null : payload.get(name);
        if (node == null || node.isNull()) {
            return "?";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
