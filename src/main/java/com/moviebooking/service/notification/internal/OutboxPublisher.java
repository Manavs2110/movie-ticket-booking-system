package com.moviebooking.service.notification.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.config.AppProperties;
import com.moviebooking.dto.notification.BookingEventMessage;
import com.moviebooking.model.notification.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The only scheduled job (LLD §10.2). Every 2 s it publishes due NOT_STARTED outbox rows to Kafka and marks them
 * IN_QUEUE once Kafka acknowledges. Reminders are rows due in the future, so the same query picks them up.
 * <ul>
 *   <li>Failed or timed out → attempts+1, retry after min(2^attempts s, 5 min); FAILED after max attempts.</li>
 *   <li>A full batch runs again immediately, so release-day bursts catch up.</li>
 *   <li>{@code FOR UPDATE SKIP LOCKED}: safe with several app instances.</li>
 *   <li>Kafka down: rows stay NOT_STARTED with growing backoff; bookings are unaffected.</li>
 * </ul>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long MAX_BACKOFF_SECONDS = 300;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, BookingEventMessage> kafka;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration ackTimeout;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, BookingEventMessage> kafka,
                           ObjectMapper objectMapper, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.topic = props.outbox().topic();
        this.batchSize = props.outbox().batchSize();
        this.maxAttempts = props.outbox().maxAttempts();
        this.ackTimeout = props.outbox().ackTimeout();
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval}")
    public void scheduledPublish() {
        try {
            publishDue();
        } catch (RuntimeException e) {
            log.warn("Outbox publish run failed: {}", e.getMessage());
        }
    }

    /** @return number of rows published (acknowledged) in this run */
    public int publishDue() {
        int published = 0;
        while (true) {
            BatchResult batch = tx.execute(status -> publishBatch());
            if (batch == null) {
                return published;
            }
            published += batch.acknowledged();
            if (batch.size() < batchSize) {
                return published;
            }
        }
    }

    private record BatchResult(int size, int acknowledged) {
    }

    private BatchResult publishBatch() {
        List<OutboxEvent> rows = jdbc.query("""
                SELECT * FROM outbox_event
                WHERE status = 'NOT_STARTED' AND next_attempt_at <= now()
                ORDER BY id LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, OutboxServiceImpl.ROW_MAPPER, batchSize);

        // 1. send everything, 2. wait for all acknowledgements (bounded), 3. record each outcome
        List<CompletableFuture<SendResult<String, BookingEventMessage>>> sends = new ArrayList<>();
        for (OutboxEvent row : rows) {
            try {
                sends.add(kafka.send(topic, String.valueOf(row.aggregateId()), toMessage(row)));
            } catch (RuntimeException e) {
                sends.add(CompletableFuture.failedFuture(e));
            }
        }
        long deadline = System.nanoTime() + ackTimeout.toNanos();
        int acknowledged = 0;
        for (int i = 0; i < rows.size(); i++) {
            OutboxEvent row = rows.get(i);
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                sends.get(i).get(remaining, TimeUnit.NANOSECONDS);
                jdbc.update("UPDATE outbox_event SET status = 'IN_QUEUE', sent_at = now(), attempts = attempts + 1 WHERE id = ?",
                        row.id());
                acknowledged++;
            } catch (Exception e) {
                recordFailure(row, e);
            }
        }
        return new BatchResult(rows.size(), acknowledged);
    }

    private BookingEventMessage toMessage(OutboxEvent row) {
        try {
            JsonNode payload = objectMapper.readTree(row.payload());
            return new BookingEventMessage(row.id(), row.eventType(), row.aggregateId(), row.createdAt(), payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt outbox payload for event " + row.id(), e);
        }
    }

    private void recordFailure(OutboxEvent row, Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String error = truncate(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        int attempts = row.attempts() + 1;
        if (attempts >= maxAttempts) {
            log.warn("Outbox event {} FAILED after {} attempts: {}", row.id(), attempts, error);
            jdbc.update("UPDATE outbox_event SET status = 'FAILED', attempts = ?, last_error = ? WHERE id = ?",
                    attempts, error, row.id());
        } else {
            long backoff = Math.min(1L << Math.min(attempts, 20), MAX_BACKOFF_SECONDS);
            log.info("Outbox event {} attempt {} failed, retry in {}s: {}", row.id(), attempts, backoff, error);
            jdbc.update("""
                    UPDATE outbox_event SET attempts = ?, last_error = ?, next_attempt_at = now() + make_interval(secs => ?)
                    WHERE id = ?
                    """, attempts, error, backoff, row.id());
        }
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
