package com.moviebooking.service.notification.internal;

import com.moviebooking.dto.notification.BookingEventMessage;
import com.moviebooking.model.notification.EventType;
import com.moviebooking.service.booking.BookingQueryService;
import com.moviebooking.service.notification.NotificationSender;
import com.moviebooking.service.notification.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Notification consumer (LLD §10.4). Kafka delivers at least once, so each event is deduplicated through its own
 * outbox row: {@code markProcessed} flips it to PROCESSED in the same transaction as the send. If sending throws, that
 * rolls back and the retry topic tries again; after the retries the event lands on the dead-letter topic.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final OutboxService outbox;
    private final BookingQueryService bookings;
    private final NotificationSender sender;
    private final TransactionTemplate tx;
    private final AtomicInteger deadLettered = new AtomicInteger();

    public NotificationConsumer(OutboxService outbox, BookingQueryService bookings, NotificationSender sender,
                                TransactionTemplate tx) {
        this.outbox = outbox;
        this.bookings = bookings;
        this.sender = sender;
        this.tx = tx;
    }

    @RetryableTopic(attempts = "${app.notification.retry-attempts}",
            backoff = @Backoff(delayExpression = "${app.notification.retry-delay-ms}", multiplier = 2),
            numPartitions = "6", replicationFactor = "1")
    @KafkaListener(topics = "${app.outbox.topic}", groupId = "${app.notification.consumer-group}",
            autoStartup = "${app.notification.listener-enabled:true}")
    public void on(BookingEventMessage event) {
        tx.executeWithoutResult(status -> {
            // IN_QUEUE → PROCESSED in the same transaction as the send: if sending throws, it rolls back and the
            // retry sees the event as unprocessed; a redelivery after success finds PROCESSED and skips.
            if (!outbox.markProcessed(event.eventId())) {
                log.info("[consumer] event {} already processed (or cancelled), skipping", event.eventId());
                return;
            }
            // A reminder may already have been in Kafka when the booking was cancelled: re-check before sending.
            if (event.type() == EventType.SHOW_REMINDER && !bookings.isStillConfirmedAndScheduled(event.aggregateId())) {
                log.info("[consumer] reminder {} skipped: booking {} no longer confirmed", event.eventId(),
                        event.aggregateId());
                return;
            }
            sender.send(event);
        });
    }

    @DltHandler
    public void dead(BookingEventMessage event) {
        deadLettered.incrementAndGet();
        log.error("[consumer] event {} ({}) dead-lettered after retries", event.eventId(), event.type());
    }

    public int deadLetteredCount() {
        return deadLettered.get();
    }
}
