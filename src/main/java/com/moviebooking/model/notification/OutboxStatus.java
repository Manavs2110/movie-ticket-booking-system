package com.moviebooking.model.notification;

/** One lifecycle per event: NOT_STARTED → IN_QUEUE (Kafka acknowledged) → PROCESSED (consumer finished). */
public enum OutboxStatus {
    /** Written by the business transaction; waiting for the publisher (a reminder waits until it's due). */
    NOT_STARTED,
    /** Kafka acknowledged it; the consumer hasn't finished yet. */
    IN_QUEUE,
    /** The notification consumer handled it. Also the consumer's dedupe marker: a redelivery finds it here and skips. */
    PROCESSED,
    /** Could not be sent to Kafka after the maximum attempts. */
    FAILED,
    /** A future-dated reminder whose booking was cancelled before it became due. */
    CANCELLED
}
