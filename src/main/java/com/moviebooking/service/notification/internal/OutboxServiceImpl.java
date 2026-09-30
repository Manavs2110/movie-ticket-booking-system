package com.moviebooking.service.notification.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.model.notification.EventType;
import com.moviebooking.model.notification.OutboxEvent;
import com.moviebooking.model.notification.OutboxStatus;
import com.moviebooking.service.notification.OutboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes events into {@code outbox_event} (LLD §10.1). The write methods can only run inside the caller's
 * business transaction (MANDATORY): if the booking commits, its event exists; if it rolls back, it never did.
 * The table is also the event audit log: rows are kept, with {@code sent_at} (Kafka acknowledged) and
 * {@code processed_at} (consumer finished).
 */
@Service
public class OutboxServiceImpl implements OutboxService {

    static final RowMapper<OutboxEvent> ROW_MAPPER = (rs, i) -> new OutboxEvent(
            rs.getLong("id"),
            EventType.valueOf(rs.getString("event_type")),
            rs.getLong("aggregate_id"),
            rs.getString("payload"),
            OutboxStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            toInstant(rs.getTimestamp("next_attempt_at")),
            rs.getString("last_error"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("sent_at")),
            toInstant(rs.getTimestamp("processed_at")));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxServiceImpl(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** An event due now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(EventType type, Long aggregateId, Map<String, Object> payload) {
        jdbc.update("INSERT INTO outbox_event (event_type, aggregate_id, payload) VALUES (?, ?, CAST(? AS jsonb))",
                type.name(), aggregateId, toJson(payload));
    }

    /** An event due later: the publisher picks it up once {@code dueAt} has passed (used for show reminders). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(EventType type, Long aggregateId, Map<String, Object> payload, Instant dueAt) {
        jdbc.update("""
                INSERT INTO outbox_event (event_type, aggregate_id, payload, next_attempt_at)
                VALUES (?, ?, CAST(? AS jsonb), ?)
                """, type.name(), aggregateId, toJson(payload), Timestamp.from(dueAt));
    }

    /** Cancels not-yet-sent events, e.g. the reminder of a booking that was just cancelled. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelPending(Long aggregateId, EventType type) {
        return jdbc.update("""
                UPDATE outbox_event SET status = 'CANCELLED'
                WHERE aggregate_id = ? AND event_type = ? AND status = 'NOT_STARTED'
                """, aggregateId, type.name());
    }

    /**
     * Consumer-side dedupe, in the consumer's transaction: check and mark in ONE statement, so two deliveries of the same
     * event can never both pass. Returns false for a duplicate (already PROCESSED) or a cancelled reminder.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(long eventId) {
        return jdbc.update("""
                UPDATE outbox_event SET status = 'PROCESSED', processed_at = now()
                WHERE id = ? AND status NOT IN ('PROCESSED', 'CANCELLED')
                """, eventId) == 1;
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> list(OutboxStatus status, Long aggregateId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM outbox_event WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        if (aggregateId != null) {
            sql.append(" AND aggregate_id = ?");
            args.add(aggregateId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unserializable outbox payload", e);
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
