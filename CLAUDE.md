# CLAUDE.md

Guidance for AI assistants working in this repository.

## Source of truth
- `docs/HLD.md` (what and why), `docs/LLD.md` (how) and `docs/diagrams.html` (diagrams) are the spec; `README.md` summarises them. Read the relevant part before changing behaviour, and update them in the same change when behaviour changes.
- `Movie Ticket Booking System.pdf` is the original brief (SDE-2 take-home).

## Build and run
- JDK 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` if Maven picks a newer JDK).
- `docker compose up -d --build` builds the app image and starts everything: app (8080), Postgres (5434),
  Redis (6380), Kafka (9092), Adminer (8081), RedisInsight (5540), Kafka UI (8082).
- For IDE runs: `docker compose up -d postgres redis kafka`, then `mvn spring-boot:run` (stop the `app` container first).
- `mvn test` runs unit and Testcontainers integration tests (Docker required; Postgres, Redis and Kafka containers).
- `./scripts/peek.sh` shows bookings, both seat-lock layers, payments, the outbox, consumer dedupe and Kafka lag.

## Architecture rules
- Modular monolith, **layer-first packages split by feature**: `controller/<f>`, `service/<f>` (interfaces),
  `service/<f>/internal` (`…Impl` + helpers), `repository/<f>`, `model/<f>`, `dto/<f>`, plus `common` and `config`.
  Features: `auth, catalog, pricing, refundpolicy, booking, payment, notification`.
- Dependencies point one way: controller → service interface → (internal impl) → repository / model.
  Model, DTO and repository never import service or controller; controllers never import repositories.
- Nothing outside a feature's `internal` package may use it: other features and controllers depend on the
  `service/<f>` interfaces only (checked by grep in the review, keep it that way). New service = interface + `Impl`.
- A feature reads another feature's data only through that feature's service interface. When catalog needs booking
  information, it goes through `service.catalog.ShowUsageChecker`, implemented in `service.booking.internal`.
- Controllers stay thin: validate, call one service, return a DTO. Entities never leave the service layer.
- Cross-feature references in entities are plain FK ids (`Long showId`), not JPA associations.
- Admin endpoints live under `/api/admin/**` and call the same services as the customer endpoints.

## Correctness rules (don't break these)
- Postgres `seat_lock` is the only owner of a seat. Redis is a filter in front of it (`RedisSeatLockFilter`) and
  must never decide ownership; every Redis call goes through `RedisGuard` (circuit breaker) and fails open to Postgres.
- Redis lock keys start with the short initial TTL and are extended only **after** the Postgres commit
  (`TwoLayerSeatHoldService` registers after-commit hooks). Redis updates after a commit are best effort.
- The booking id is taken with `nextval('booking_id_seq')` before the Redis filter, so both layers share one id.
- Every lock time comparison uses the **database clock** (`now()` / `DbClock`), never `Instant.now()`.
- Never call the payment gateway inside a DB transaction. Use `TransactionTemplate` for short explicit transactions (see `BookingService.pay`).
- Seat ids are sorted before claiming (consistent lock order, no deadlocks). A hold is all-or-nothing: fewer claimed rows → throw, roll back.
- Events are only written through `OutboxService.publish/schedule/cancelPending` (propagation MANDATORY) inside the
  business transaction. Never send to Kafka from business code: `OutboxPublisher` (the only scheduled job) publishes
  due rows, keyed by booking id. Reminders are `schedule(...)`d rows due at show start − 2 h, never a separate job.
- Outbox status lifecycle: `NOT_STARTED` → `IN_QUEUE` (publisher, Kafka acked) → `PROCESSED` (consumer). The consumer
  is at-least-once: `OutboxService.markProcessed` is a conditional UPDATE in the same transaction as the send, so a
  redelivered event is skipped. It also re-checks a reminder's booking before sending.
- A booking has at most one refund, stored on the booking row (`refund_*` columns); `payment` only tracks the charge.
- Discount usage is counted at payment with the conditional UPDATE and flagged on the booking (`discount_redeemed`,
  which the per-user limit counts); both are undone on decline or confirm failure.
- Money is `BigDecimal`, scale 2, `HALF_UP`. Business time zone is `Asia/Kolkata`.
- Schema changes go in a new Flyway migration (`V6__...`). Hibernate is `ddl-auto: validate`.

## Errors
- Throw `AppException(ErrorCode, message[, details])`. Add new codes to `ErrorCode` with their HTTP status.
- Constraint violations that can happen under races are mapped by constraint name in `GlobalExceptionHandler`.

## Tests
- Unit tests: `*Test`. Integration tests: `*IT`, extending `support.IntegrationTest` (shared Postgres, Redis and
  Kafka containers; schedulers off, call `OutboxPublisher.publishDue()` directly). The Kafka listener is off
  (`app.notification.listener-enabled=false`) except in `EventsIT`: the outbox status is shared, so another cached
  context's consumer would otherwise take its events.
- `fx.expireHold` ages the Postgres lock **and** removes the Redis keys (as their TTL would); use Awaitility for Kafka.
- Build isolated data with `TestFixtures` (fresh customer, fresh screen and show). Never depend on another test's data.
- Control time by moving timestamps in the DB (`fx.expireHold`, `fx.startShow`), never with `sleep`.
- Concurrency tests release threads together with a `CountDownLatch` and assert exact winner counts.
- Every behaviour change needs a test; run `mvn test` before declaring done.

## Style
- Constructor injection, records for DTOs, no Lombok.
- Match the surrounding comment density: explain *why* (invariants, races), not *what*.
