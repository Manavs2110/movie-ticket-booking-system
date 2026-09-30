#!/usr/bin/env bash
# Quick look at what's inside Postgres, Redis and Kafka while the app runs.
#   ./scripts/peek.sh            overview: bookings, seat locks (both layers), payments/refunds, outbox, Kafka lag
#   ./scripts/peek.sh sql "..."  run any SQL
#   ./scripts/peek.sh redis ...  run any redis-cli command, e.g. ./scripts/peek.sh redis TTL "lock:{show:40}:seat:241"
#   ./scripts/peek.sh kafka      tail the booking-events topic (Ctrl-C to stop)
set -euo pipefail

PSQL=(docker exec -i mtbs-postgres psql -U moviebooking -d moviebooking -P pager=off)
REDIS=(docker exec -i mtbs-redis redis-cli)
KAFKA_BIN=/opt/kafka/bin

case "${1:-overview}" in
  sql)   shift; "${PSQL[@]}" -c "$*" ;;
  redis) shift; "${REDIS[@]}" "$@" ;;
  kafka)
    docker exec -it mtbs-kafka $KAFKA_BIN/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
      --topic booking-events --from-beginning --property print.key=true ;;
  overview)
    echo "== Latest bookings";
    "${PSQL[@]}" -c "SELECT b.id, u.email, b.show_id, b.status,
                            CASE WHEN b.status = 'HELD' AND b.hold_expires_at <= now() THEN 'EXPIRED (derived)' END AS effective,
                            b.total_amount, b.hold_expires_at, b.created_at
                     FROM booking b JOIN app_user u ON u.id = b.user_id ORDER BY b.id DESC LIMIT 10;"
    echo "== Seat locks, layer 2: Postgres (the owner)";
    "${PSQL[@]}" -c "SELECT sl.show_id, s.row_label || s.seat_number AS seat, sl.booking_id,
                            CASE WHEN sl.booked THEN 'BOOKED' WHEN sl.held_until > now() THEN 'LOCKED' ELSE 'expired' END AS state,
                            sl.held_until
                     FROM seat_lock sl JOIN seat s ON s.id = sl.seat_id ORDER BY sl.show_id DESC, s.id LIMIT 20;"
    echo "== Seat locks, layer 1: Redis filter (key → value, TTL seconds)";
    for k in $("${REDIS[@]}" --scan --pattern 'lock:*' | head -20); do
      echo "$k → $("${REDIS[@]}" GET "$k") (ttl $("${REDIS[@]}" TTL "$k"))"
    done
    echo "== Payments and refunds";
    "${PSQL[@]}" -c "SELECT p.booking_id, p.amount, p.status AS payment, b.refund_amount AS refund, b.refund_percent AS pct,
                            b.refund_reason, b.refund_status, b.discount_redeemed
                     FROM payment p JOIN booking b ON b.id = p.booking_id ORDER BY p.id DESC LIMIT 10;"
    echo "== Outbox / event log (NOT_STARTED → IN_QUEUE (Kafka acked) → PROCESSED (consumer done); reminders wait until show − 2 h)";
    "${PSQL[@]}" -c "SELECT id, event_type, aggregate_id AS booking, status, attempts, next_attempt_at, sent_at, processed_at
                     FROM outbox_event ORDER BY id DESC LIMIT 10;"
    echo "== Kafka consumer group 'notification' (LAG = events not yet handled)";
    docker exec mtbs-kafka $KAFKA_BIN/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
      --describe --group notification 2>/dev/null | awk 'NR==1 || /booking-events/ {print $2, $3, $4, $5, $6}' | column -t || true
    echo "== Other Redis keys (rate limits, seat-map micro-cache, Spring caches)";
    for p in 'rl:*' 'seatmap:*' 'mtbs::*'; do
      for k in $("${REDIS[@]}" --scan --pattern "$p" | head -10); do echo "$k → ttl $("${REDIS[@]}" TTL "$k")"; done
    done
    ;;
  *) echo "usage: $0 [overview | sql \"<query>\" | redis <command...> | kafka]"; exit 1 ;;
esac
