---
name: verify-booking-system
description: Verify the movie ticket booking system end to end before calling a change done — runs the full test suite (unit + Testcontainers integration), starts a fresh Docker stack, runs the Postman customer-lifecycle collection against it with newman, and tears everything down. Use after any change to schema, booking/locking logic, events or APIs, and before submitting.
---

# Verify the booking system

The rule in this project: **a change is done only when all of these pass.** Run them in order and stop at the first failure.

## One command

```bash
./.claude/skills/verify-booking-system/verify.sh            # everything
./.claude/skills/verify-booking-system/verify.sh --skip-tests   # only the live-stack check
```

The script prints `PASS` / `FAIL` for each step and exits non-zero on the first failure.

## What it does

1. **Preconditions** — Docker is running; JDK 21 is found (Maven must not pick a newer JDK); `npx` is available for newman.
2. **Test suite** — `mvn -q clean test` with JDK 21. Expected: **95 tests, 0 failures** (39 unit + 56 integration on
   real Postgres, Redis and Kafka via Testcontainers). Always `clean`: a stale migration left in `target/classes`
   makes Flyway fail.
3. **Fresh stack** — `docker compose down -v` then `docker compose up -d --build`; wait (up to 3 min) until
   `GET /api/cities` answers. A fresh database matters: the Postman collection uses literal ids
   (screen 7, show 337, seats 721/731, booking 1).
4. **End-to-end run** — `newman` runs `scripts/demo/postman/MovieBooking-Lifecycle-16.postman_collection.json`:
   register → admin schedules a show → theaters near me → seat map → price preview → lock → rival gets 409 →
   pay → same payment again (no second charge) → events → history → cancel with refund → events.
   Expected: **16 requests, 0 failed assertions**.
5. **Data checks** — after the run, in Postgres: booking 1 is `CANCELLED` with `refund_status = SUCCESS`,
   exactly one `payment` row for it, no `seat_lock` rows left for show 337, and its outbox events end
   `PROCESSED` (reminder `CANCELLED`).
6. **Teardown** — `docker compose down -v`, so the next run (or a demo) starts clean.

## When something fails

| Symptom | Likely cause |
|---|---|
| Tests fail with a Flyway checksum / validation error | An existing migration was edited. Add a new `V6__…` instead, or `docker compose down -v` for the dev DB. |
| Tests can't start containers | Docker isn't running. |
| Postman step 3 or 4 returns 409 | The stack wasn't fresh; the script resets it, so re-run. |
| Step 13/16 events not `PROCESSED` in the data check | The publisher runs every 2 s; re-run the check after a few seconds. |

Report the result as: tests (count / failures), newman (requests / failed assertions), data checks, and anything
that failed with its output. Never report success for a step that was skipped.
