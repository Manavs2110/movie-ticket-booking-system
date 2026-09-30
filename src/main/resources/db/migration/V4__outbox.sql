-- Every business event, written in the same transaction as the change (transactional outbox) and kept as the audit log.
-- Lifecycle: NOT_STARTED → IN_QUEUE (Kafka acknowledged) → PROCESSED (notification consumer finished).
-- FAILED = could not be sent after max attempts; CANCELLED = a future reminder whose booking was cancelled.
CREATE TABLE outbox_event (
  id              BIGSERIAL PRIMARY KEY,                -- = eventId everywhere (Kafka message, consumer dedupe)
  event_type      VARCHAR(40) NOT NULL,
  aggregate_id    BIGINT NOT NULL,                      -- booking id (Kafka key → per-booking order)
  payload         JSONB NOT NULL,
  status          VARCHAR(12) NOT NULL DEFAULT 'NOT_STARTED'
                  CHECK (status IN ('NOT_STARTED','IN_QUEUE','PROCESSED','FAILED','CANCELLED')),
  attempts        INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),   -- now for normal events; show start − 2 h for reminders
  last_error      VARCHAR(500),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at         TIMESTAMPTZ,                          -- Kafka acknowledged
  processed_at    TIMESTAMPTZ                           -- consumer finished
);
CREATE INDEX ix_outbox_due       ON outbox_event(next_attempt_at) WHERE status = 'NOT_STARTED';
CREATE INDEX ix_outbox_aggregate ON outbox_event(aggregate_id, event_type);   -- cancel a booking's reminder
