# Movie Ticket Booking System

A movie ticket booking backend for many cities, theaters, screens and shows, with **seat-level booking**. Seats are held
for a limited time and released automatically, prices follow regular / premium / weekend tiers with discount codes,
payment confirms the booking, and cancellations are refunded under configurable policies. Confirmation and reminder
notifications never block a booking.

When thousands of people try to book the same seat at the same moment, **exactly one gets it. A seat is never sold twice.**

**Stack:** Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Redis 7 · Kafka 3.9 · Resilience4j · Flyway · Testcontainers

## 🎥 Video walkthrough

**[Watch on Loom →](https://www.loom.com/share/1704ff79562346179fe1a5c0bdc767df)** — approach, design, tech stack, AI workflow, testing and a live demo.

## Quick links

| | |
|---|---|
| **Design docs** | [docs/HLD.md](docs/HLD.md) (what and why) · [docs/LLD.md](docs/LLD.md) (how: schema, flows, APIs, test plan) |
| **Diagrams** | [docs/diagrams.html](docs/diagrams.html) (open in a browser): architecture, packages, class diagram, ER diagram, sequence diagrams for every flow, state machines |
| **HLD sketch** | [Excalidraw](https://excalidraw.com/#json=o2eKJEHBE2mQhD0nls4W-,9Qe0FpP-GsHtyHzEGKnKww) |
| **API docs** | Swagger UI at http://localhost:8080/swagger-ui.html once the app is running |
| **Try the API** | [Postman collection](scripts/demo/postman/MovieBooking-Lifecycle-16.postman_collection.json) · [api.http](api.http) |
| **AI instructions** | [CLAUDE.md](CLAUDE.md) · skill [verify-booking-system](.claude/skills/verify-booking-system/SKILL.md) |

## Contents

1. [Approach and scope](#1-approach-and-scope)
2. [Features](#2-features)
3. [Running it](#3-running-it)
4. [API](#4-api)
5. [Design](#5-design)
6. [Scale and concurrency](#6-scale-and-concurrency)
7. [Tech stack](#7-tech-stack)
8. [Testing](#8-testing)
9. [Assumptions](#9-assumptions)
10. [AI workflow](#10-ai-workflow)
11. [Project layout](#11-project-layout)

---

## 1. Approach and scope

**How I approached it**

1. **Start from the hard problems.** Four drive the design: the same seat clicked by many people at once, holds that
   must expire on their own, notifications that must never block a booking, and pricing and refund rules that admins
   control.
2. **Design before code.** A high-level and a low-level design first; the result is in [docs/diagrams.html](docs/diagrams.html).
3. **Core flow first:** lock → pay → confirm → cancel. Then admin features, discounts, refunds and notifications.
4. **Prove the risky parts** with concurrency and failure tests on real Postgres, Redis and Kafka.

**Scope**

| Built (in scope) | Deliberately left out (out of scope) |
|---|---|
| REST APIs for every flow, documented in Swagger | UI |
| PostgreSQL, schema versioned with Flyway | Microservices: this is one modular monolith |
| Role-based access (`ADMIN`, `CUSTOMER`) | OAuth / SSO / MFA: HTTP Basic is used |
| Input validation and one consistent error format | Deployment and CI/CD (Docker Compose is only for running locally) |
| Unit and integration tests | Production monitoring and alerting |

**Beyond the brief:** "near me" theater search, a live seat map with ETag/304 polling, per-user rate limits, idempotent
payments, automatic refunds when seats can't be confirmed after a charge, show reminders, admin cancel-show with full
refunds, a circuit breaker for Redis, and an outbox → Kafka pipeline with retries and a dead-letter topic.

## 2. Features

**Customers**
- Register and log in.
- Browse movies by city, date, language and genre; find theaters showing a movie, **nearest first**.
- See a live **seat map**: available, held, booked or blocked, with seat type and price.
- **Lock 1–10 seats, all or nothing**, for 10 minutes; released automatically if not paid.
- Preview the price with a discount code.
- **Pay** with an `Idempotency-Key`, so a retry or double-click never charges twice.
- Get notified on confirmation, cancellation and refund, plus a **reminder 2 hours before the show**.
- **Cancel** and receive a refund according to the policy; view booking history.

**Admins**
- Manage cities, theaters (with coordinates and an optional refund-policy override), screens and **seat layouts**.
- Manage movies and **schedule shows**; overlapping shows on a screen are rejected by the database.
- Set **prices per show**: regular, premium and a weekend multiplier.
- Manage **discount codes**: flat or percentage, cap, minimum order, validity and usage limits.
- Manage **refund policies**: a default plus per-theater overrides.
- **Cancel a show**: every booking refunded in full and notified; safe to re-run.
- Inspect bookings per show, failed refunds and the event log.

## 3. Running it

Only Docker is needed:

```bash
docker compose up -d --build     # app + Postgres + Redis + Kafka + admin UIs
curl localhost:8080/api/cities   # ready after ~30 s (schema and seed data are created on first start)
docker compose logs -f app       # mock notifications appear here as [notify] lines
docker compose down -v           # stop and wipe all data
```

To run the app from an IDE instead: `docker compose up -d postgres redis kafka`, then `mvn spring-boot:run` (JDK 21).

| Service | Address | Login |
|---|---|---|
| API | http://localhost:8080/api | HTTP Basic (seed accounts below) |
| Swagger UI | http://localhost:8080/swagger-ui.html | *Authorize* with a seed account |
| Adminer (Postgres) | http://localhost:8081 | server `postgres`, user / password / database `moviebooking` |
| RedisInsight | http://localhost:5540 | pre-registered as `moviebooking-redis` |
| Kafka UI | http://localhost:8082 | — |
| PostgreSQL / Redis / Kafka | `localhost:5434` / `6380` / `9092` | Postgres: `moviebooking` / `moviebooking` |

| Seed account | Email | Password |
|---|---|---|
| Admin | `admin@moviebooking.com` | `Admin@123` |
| Customer | `demo@moviebooking.com` | `Customer@123` |

**Seed data:** 2 cities, 3 theaters with 2 screens each (10 rows × 12 seats, last two rows premium), 4 movies, and 4 shows
per screen per day for 14 days from the first start. Discount codes `FIRST50`, `WEEKEND20`, `EXPIRED10`; refund policies
*Standard* (default) and *Flexible*.

**Watch it live**

| Command | Shows |
|---|---|
| `./scripts/redis-locks.sh` | Seat-lock keys in Redis with value and TTL, refreshed every second |
| `./scripts/watch-locks.sh <showId>` | Both lock layers, bookings and events for one show |
| `./scripts/peek.sh` | Bookings, locks, payments, refunds, events and Kafka lag |

## 4. API

| Access | Endpoints |
|---|---|
| Public | `GET /cities`, `/cities/{id}/theaters`, `/cities/{id}/movies`, `/movies/{id}`, `/movies/{id}/shows`, `/shows/{id}`, `/shows/{id}/seats` |
| Auth | `POST /auth/register`, `GET /auth/me` |
| Customer | `POST /discounts/preview`, `POST /bookings`, `POST /bookings/{id}/pay`, `POST /bookings/{id}/cancel`, `GET /bookings/{id}`, `GET /me/bookings` |
| Admin | `/admin/cities`, `/admin/theaters`, `/admin/theaters/{id}/screens`, `/admin/screens/{id}/seats`, `/admin/movies`, `/admin/shows`, `/admin/shows/{id}/cancel`, `/admin/shows/{id}/bookings`, `/admin/discounts`, `/admin/refund-policies`, `/admin/refunds`, `/admin/outbox` |

A booking in three calls:

```bash
C='demo@moviebooking.com:Customer@123'
curl -s localhost:8080/api/shows/34/seats                                    # pick seat ids
curl -s -u $C -H 'Content-Type: application/json' \
     -d '{"showId":34,"seatIds":[241,242],"discountCode":"FIRST50"}' localhost:8080/api/bookings
curl -s -u $C -X POST -H 'Idempotency-Key: my-key-1' localhost:8080/api/bookings/<bookingId>/pay
```

The mock payment gateway declines `{"paymentToken":"tok_decline"}` and approves anything else.

Errors always look the same: `{"code", "message", "details", "path", "timestamp"}`. For example `409 SEATS_UNAVAILABLE`
lists the taken seats in `details.unavailableSeatIds`, and `429 RATE_LIMITED` comes with a `Retry-After` header.

## 5. Design

```
Customer → rate limit → seat filter (Redis) → seat claim (Postgres) → pay + confirm → outbox → Kafka → notification
```

**Redis filters. Postgres decides. Kafka tells the customer.** All diagrams: [docs/diagrams.html](docs/diagrams.html).

| Decision | Why |
|---|---|
| **Two-layer seat locks** | A Redis script claims all requested seats or none. At a busy release it rejects most requests in memory (~2 ms) before they reach the database. Survivors claim the seats in Postgres, where the primary key `(show, seat)` allows only one owner. If the two layers ever disagree, Postgres wins. |
| **Holds expire by time, not by a job** | A hold is a `held_until` timestamp (and a Redis TTL). Once it passes, the seat is free and the next claim takes it. All time checks use the database clock, so app instances can't disagree. |
| **Short initial Redis TTL** | Redis keys start at 30 s and are extended only after Postgres commits, so a crash in between can't block a seat for long. |
| **No deadlocks, no partial holds** | Seats are claimed in sorted order inside one transaction. |
| **Payment outside the transaction** | The gateway is called with no database transaction open, so a slow provider never holds locks. If the charge succeeds but the seats can't be confirmed, the payment is refunded automatically. |
| **Idempotent payment** | The `Idempotency-Key` is unique; the same key returns the existing result instead of charging again. |
| **Transactional outbox → Kafka** | Every event row is written in the same transaction as the change, so an event exists exactly when the change does. A publisher sends due rows to Kafka every 2 s. Each row moves `NOT_STARTED` → `IN_QUEUE` → `PROCESSED`, which also lets the consumer skip duplicates. If Kafka is down, bookings are unaffected and events wait. |
| **Reminders as scheduled events** | A reminder is an outbox row due 2 hours before the show; cancelling the booking cancels it. |
| **Redis never breaks correctness** | A circuit breaker skips Redis when it's unhealthy: seat locking falls back to Postgres (slower, still correct), caches read Postgres, and rate limits let requests through. |
| **Cheap live seat map** | A 2-second cache per show plus ETags: 20K polls per second become about 50 database queries, and unchanged maps return `304`. |
| **Modular monolith** | One deployable, so booking, payment and events share a transaction. Features only talk through interfaces and can be split out later. |

**Data model:** 14 tables — `app_user`, `city`, `theater`, `screen`, `seat`, `movie`, `show` (with its prices),
`discount_code`, `refund_policy` (rules stored as JSON), `booking` (with its refund), `booking_item` (price snapshot per
seat), `seat_lock` (rows only for held or sold seats), `payment` and `outbox_event`.

## 6. Scale and concurrency

"Concurrency" means two different things:

- **Volume:** the brief's peak is 10K users on one popular show and 100K+ across the platform. These are **assumed**
  figures, and they count people online, not requests: 100K people clicking within ~5 seconds is about 20K lock
  requests per second.
- **Correctness:** two, or two thousand, requests for the same seat at the same instant. Exactly one must win.

At an assumed release peak (10 popular shows open at once, 10K people each, ≤ 500 seats each):

| | Postgres only | Redis in front (built) |
|---|---|---|
| Work | 100K lock transactions ≈ 10 s of database work, arriving within 5 s | 40K Redis operations per second: one node, ~40 % busy |
| Reaches Postgres | Every request | Only the ~2,000 winners |
| Latency | The last click waits **~5 s** | A rejected request is answered in **~2 ms** |

*Planning figures: Postgres ≈ 10K write transactions/s, Redis ≈ 100K operations/s per node.* On a normal day
(~10 bookings/s) Postgres alone would be enough; the Redis layer is for release bursts. Beyond this scale, a virtual
waiting room would admit users at a controlled rate.

## 7. Tech stack

| Choice | Why |
|---|---|
| Java 21 + Spring Boot 3.5 | Required stack; security, data access, validation, caching and Kafka in one framework |
| PostgreSQL 16 | Real transactions and constraints: a primary key prevents double booking, an exclusion constraint prevents overlapping shows, `SKIP LOCKED` lets several publishers share the outbox |
| Redis 7 | Sub-millisecond seat filter, per-user rate limits and caches |
| Kafka 3.9 | Notifications off the booking path, with retries and a dead-letter topic |
| Flyway | Versioned schema and seed data |
| Resilience4j | Circuit breaker: a Redis outage falls back to Postgres |
| Testcontainers | Integration tests against real Postgres, Redis and Kafka |
| springdoc-openapi | Swagger UI |

## 8. Testing

```bash
mvn test    # 95 tests (39 unit + 56 integration); Docker must be running
```

| Area | Tests | What it proves |
|---|---|---|
| Unit | 39 | Pricing tiers and weekend rules, discount maths, refund boundaries (exactly 24 h / 4 h), booking status |
| Booking flow | 23 | Lock → pay → confirm; expired lock → late payment rejected **without charging**; idempotent payment; declined card; automatic refund; refund by policy; admin cancel-show |
| API and security | 11 | Public browsing, 401/403, validation errors, rate limiting, near-me ordering, overlapping shows rejected |
| Concurrency | 4 | 50 threads on one seat → **exactly 1 winner**; overlapping multi-seat requests → no deadlock; last discount use → 1 winner |
| Two-layer lock | 5 | **500 threads on one seat → 1 winner**, with at most 3 reaching Postgres |
| Redis outage | 1 | Redis stopped → still exactly 1 winner |
| Seat map | 4 | ETag / 304, cache and invalidation |
| Events | 8 | Exactly one notification per event; duplicates skipped; reminders; retries and dead-letter; **Kafka paused → bookings still succeed** |

Concurrency tests release all threads at the same instant and assert exact winner counts. Time-dependent cases move
timestamps in the database instead of sleeping.

**Interactive demos** (Python 3, no dependencies):

```bash
python3 scripts/demo/lifecycle.py   # one customer's journey, step by step, with Postgres and Redis shown after each step
python3 scripts/demo/stress.py      # 500 users on one seat, rate limits, lock expiry, Redis stopped, Kafka paused, and more
```

The [Postman collection](scripts/demo/postman/MovieBooking-Lifecycle-16.postman_collection.json) runs the same customer
journey in 16 requests (start from a fresh database and run them in order).

## 9. Assumptions

**Seat holds**
- A lock lasts **10 minutes**. When payment starts, it is extended to cover a **5-minute payment window**.
- **1–10 seats** per booking, all or nothing, and **one active lock per customer per show**.
- An expired hold is reported as `EXPIRED`; no background job is needed.

**Pricing and discounts**
- Seat types are regular and premium. **Prices are set per show**, with a weekend multiplier (default 1.25).
- Weekend means Saturday or Sunday in **IST**.
- The price of each seat is **frozen at lock time**; later price changes don't affect existing bookings.
- One discount code per booking. It is checked when seats are locked but **only counted at payment**, so abandoned
  locks don't use it up.
- Currency is INR.

**Refunds**
- The theater's refund policy applies if it has one, otherwise the default: **24 h or more before the show → 100 %,
  4 h or more → 50 %, otherwise 0 %**.
- A show that has started can't be cancelled by the customer. An admin cancelling a show always refunds **100 %**.

**Catalog**
- A show occupies its screen for the movie's length plus **15 minutes** of cleaning.
- Seats are never deleted, only blocked, because bookings refer to them.

**Scale and environment**
- 10K concurrent users per popular show, 100K+ across the platform (assumed); 1,000+ theaters, 10K+ shows a day,
  200–500 seats per show.
- Rate limits per user per minute: 10 lock requests, 5 payments, 100 other requests.
- The payment gateway and email / SMS are **mocks** behind interfaces.
- Authentication is HTTP Basic (the brief rules out advanced auth). Everyone registers as a customer; the admin account
  is seeded. Requesting someone else's booking returns 404, so booking ids can't be probed.

## 10. AI workflow

Built with **Claude Code** as a pair programmer, not an autopilot.

- **[CLAUDE.md](CLAUDE.md)** is its rulebook: package structure, the correctness rules it must never break (Postgres is
  the only owner of a seat, Redis failures fall back to Postgres, events only through the outbox, no payment call inside
  a transaction) and how to test.
- **The loop:** I own the design → Claude implements one change at a time → I review and push back → a change only
  counts once the full test suite and a run on a fresh stack pass.
- **Skill — [verify-booking-system](.claude/skills/verify-booking-system/SKILL.md):** codifies the verification loop used
  throughout development, and was run as the final check before submission: full test suite → fresh Docker stack →
  Postman customer-lifecycle collection via newman → database checks → teardown. Run it with
  `./.claude/skills/verify-booking-system/verify.sh` (or ask Claude Code to verify the system).
- **Design decisions that came out of review:** Redis locks became a two-layer lock with Postgres as the owner; the
  hold-cleanup job was dropped in favour of expiry by timestamp; prices moved onto the show; a separate processed-events
  table was replaced by the outbox status; the schema was trimmed from 19 to 14 tables; and asking *"what if the Redis
  cleanup fails?"* uncovered an edge case worth handling.

## 11. Project layout

```
src/main/java/com/moviebooking
├── controller/<feature>      REST controllers: validate → call one service → return a DTO
├── service/<feature>         service interfaces, the only way features talk to each other
│   └── internal/             implementations (incl. booking/seathold: the two-layer seat lock)
├── repository/<feature>      Spring Data repositories
├── model/<feature>           entities and enums
├── dto/<feature>             request and response records
├── common                    errors, database clock, circuit breaker, rate limiting
└── config                    security, caching, Redis scripts, Kafka, scheduling
src/main/resources/db/migration    Flyway migrations (schema and seed data)
src/test/java/com/moviebooking     unit tests; integration/ (Testcontainers); support/ (fixtures)
scripts/                           demo scripts, live views, Postman collection
.claude/skills/                    Claude Code skill used for verification
docs/                              HLD.md, LLD.md, diagrams.html
Dockerfile, docker-compose.yml     run everything locally
```

Features: `auth`, `catalog`, `pricing`, `refundpolicy`, `booking`, `payment`, `notification`.
