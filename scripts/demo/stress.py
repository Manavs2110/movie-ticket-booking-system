#!/usr/bin/env python3
"""Concurrency, rate limits and failure drills, one scenario per Enter.

    python3 scripts/demo/stress.py                 (add --no-peek to skip the Postgres / Redis peeks)

Setup creates its own screen (5 rows × 10 seats), one show and 560 customers, so every run starts clean.
  1. 500 customers click the same seat at the same instant          → exactly 1 winner
  2. Overlapping multi-seat requests                                 → all or nothing, no deadlock
  3. Double-click on "Book"                                          → one booking
  4. Five payment requests with one Idempotency-Key at once         → one charge
  5. Discount code with 1 use left, 10 customers pay at once        → 1 discount
  6. Rate limit: 12 lock requests in a row                           → 10 pass, 2 × 429
  7. Lock expiry: someone else takes the seat, the late payer        → 409, not charged
  8. Redis stopped mid-sale                                          → still exactly 1 winner
  9. Kafka paused                                                    → bookings unaffected, events delivered after
Scenarios 8 and 9 stop and restart containers (docker stop/start mtbs-redis, docker pause/unpause mtbs-kafka).
"""
import sys
import time
import uuid
from datetime import datetime, timedelta

sys.path.insert(0, __import__("os").path.dirname(__file__))
from common import (ADMIN, IST, PASSWORD, bad, banner, blue, bold, check, dim, docker, fire_together, green, http,
                    note, ok, redis_locks, register_users, require_stack, seat_grid, show_curl, curl_args, sql,
                    sql_value, step_header, tally, wait_enter, yellow)

RUN = uuid.uuid4().hex[:6]
ctx = {}
SCENARIOS = []


def scenario(title, why):
    def register(fn):
        SCENARIOS.append((title, why, fn))
        return fn
    return register


def seat(label):
    return ctx["seats"][label]


def hold(user, seat_ids, code=None):
    body = {"showId": ctx["show"], "seatIds": seat_ids}
    if code:
        body["discountCode"] = code
    return http("POST", "/bookings", auth=user, body=body)


def pay(user, booking_id, key=None):
    return http("POST", f"/bookings/{booking_id}/pay", auth=user,
                headers={"Idempotency-Key": key or uuid.uuid4().hex}, body={"paymentToken": "tok_visa"})


def print_example(method, path, auth, body=None, headers=None, prefix="each request"):
    print(dim(f"\n  {prefix}:"))
    show_curl(curl_args(method, path, auth, body, headers))


def user_pool(n):
    taken, ctx["next_user"] = ctx["users"][ctx["next_user"]:ctx["next_user"] + n], ctx["next_user"] + n
    return taken


def redis_fallbacks_since(started):
    """How many Redis calls fell back to Postgres (timeouts, breaker open) since `started` (a UTC datetime)."""
    out = docker("logs", "mtbs-app", "--since", started.strftime("%Y-%m-%dT%H:%M:%SZ"))
    return sum(1 for line in (out.stdout + out.stderr).splitlines() if "falling back" in line)


def wait_for_redis_breaker(user, timeout=40):
    """The rate limiter fails open while the Redis breaker is OPEN / HALF_OPEN; wait until calls count again."""
    _, me, _ = http("GET", "/auth/me", auth=user)
    deadline = time.time() + timeout
    while time.time() < deadline:
        http("GET", "/auth/me", auth=user)
        if docker("exec", "mtbs-redis", "redis-cli", "--scan", "--pattern", f"rl:{me['id']}:default:*").stdout.strip():
            return True
        time.sleep(1)
    return False


def seat_row(row, title):
    status, seat_map, _ = http("GET", f"/shows/{ctx['show']}/seats")
    print(dim(f"\n  seat map, {title}:"))
    seat_grid({"seats": [s for s in seat_map["seats"] if s["row"] == row]})


# ---------------------------------------------------------------- setup

def setup():
    print(dim("\n  Admin creates a fresh screen (rows A–E × 10 seats) and one show, then 560 customers register."))
    status, screen, _ = http("POST", "/admin/theaters/1/screens", auth=ADMIN, body={
        "name": f"Load {RUN}",
        "rows": [{"rowLabel": r, "seatCount": 10, "seatType": "PREMIUM" if r == "E" else "REGULAR"} for r in "ABCDE"]})
    if status != 201:
        bad(f"could not create the screen: {status} {screen}")
        sys.exit(1)
    _, seats, _ = http("GET", f"/admin/screens/{screen['id']}/seats", auth=ADMIN)
    ctx["seats"] = {s["label"]: s["id"] for s in seats}
    start = (datetime.now(IST) + timedelta(days=5)).replace(hour=19, minute=0, second=0, microsecond=0)
    status, show, _ = http("POST", "/admin/shows", auth=ADMIN, body={
        "movieId": 3, "screenId": screen["id"], "startTime": start.isoformat(), "regularPrice": 200, "premiumPrice": 300})
    if status != 201:
        bad(f"could not create the show: {status} {show}")
        sys.exit(1)
    ctx["show"] = show["id"]
    ok(f"screen {screen['id']} · show {show['id']} on {start.strftime('%a %d %b %H:%M IST')} · ₹200 / ₹300")
    t = time.time()
    ctx["users"], ctx["next_user"] = register_users(560, "user"), 0
    ok(f"560 customers registered in {time.time() - t:.1f}s")
    note(f"live view in another terminal:  ./scripts/watch-locks.sh {ctx['show']}")


# ---------------------------------------------------------------- scenarios

@scenario("500 customers, one seat, same instant", """
    500 different customers ask for seat A1 at exactly the same moment (threads released together).
    Redis SET NX turns away the losers in memory; Postgres' primary key on seat_lock is the final judge.
    Expected: exactly one 201, 499 × 409 SEATS_UNAVAILABLE, one seat_lock row, one Redis key.""")
def s1():
    users = user_pool(500)
    print_example("POST", "/bookings", users[0], {"showId": ctx["show"], "seatIds": [seat("A1")]}, prefix="× 500 of")
    t, started = time.time(), datetime.utcnow() - timedelta(seconds=1)
    results = fire_together([lambda u=u: hold(u, [seat("A1")]) for u in users])
    print(f"\n  {len(results)} responses in {time.time() - t:.1f}s:")
    counts = tally(results)
    check(counts.get("201", 0) == 1, "exactly one winner", f"{counts.get('201', 0)} winners")
    fallbacks = redis_fallbacks_since(started)
    if fallbacks:
        note(f"{fallbacks} Redis calls timed out under the burst (200 ms limit, CPU busy with 500 BCrypt logins) and")
        note("fell back to Postgres; the circuit breaker may have opened. Still one winner: Postgres decides.")
    sql(f"SELECT booking_id, seat_id, booked, held_until FROM seat_lock WHERE show_id = {ctx['show']} "
        f"AND seat_id = {seat('A1')}", "seat_lock for A1 (one row = one owner)")
    redis_locks(ctx["show"])


@scenario("Overlapping multi-seat requests", """
    9 customers each want two neighbouring seats in row B: B1+B2, B2+B3, … B9+B10, all at once.
    Seats are claimed in sorted order (no deadlock) and a request gets both seats or none (no half bookings).""")
def s2():
    users = user_pool(9)
    pairs = [[seat(f"B{i}"), seat(f"B{i + 1}")] for i in range(1, 10)]
    print_example("POST", "/bookings", users[0], {"showId": ctx["show"], "seatIds": pairs[0]}, prefix="customer 1 of 9")
    results = fire_together([lambda u=u, p=p: hold(u, p) for u, p in zip(users, pairs)])
    print()
    tally(results)
    for (status, body, _), p in zip(results, pairs):
        if status == 201:
            ok(f"booking {body['bookingId']} got {' + '.join(i['seat'] for i in body['items'])}")
    partial = sql_value(f"SELECT count(*) FROM (SELECT booking_id FROM seat_lock WHERE show_id = {ctx['show']} "
                        f"AND seat_id IN ({','.join(str(seat(f'B{i}')) for i in range(1, 11))}) "
                        f"GROUP BY booking_id HAVING count(*) <> 2) x")
    check(partial == "0", "no booking holds only one of its two seats", f"{partial} partial bookings")
    seat_row("B", "row B")


@scenario("Double-click on Book", """
    One customer's browser sends the same lock request 5 times at once. Each click is a separate request with its
    own booking id, so the first one to claim the seat wins and the other four are rejected like any other rival.
    Behind that, a partial unique index allows only one active hold per customer per show.""")
def s3():
    user = user_pool(1)[0]
    print_example("POST", "/bookings", user, {"showId": ctx["show"], "seatIds": [seat("C1")]}, prefix="× 5")
    results = fire_together([lambda: hold(user, [seat("C1")]) for _ in range(5)])
    print()
    counts = tally(results)
    check(counts.get("201", 0) == 1, "one booking created")
    sql(f"SELECT b.id, b.status, u.email FROM booking b JOIN app_user u ON u.id = b.user_id "
        f"WHERE b.show_id = {ctx['show']} AND u.email = '{user[0]}'", "bookings for that customer on this show")


@scenario("Same payment sent 5 times at once", """
    A customer holds C2, then 5 pay requests with the SAME Idempotency-Key arrive together (retrying app, flaky
    network). The booking row lock lets one through; the others get 409 PAYMENT_IN_PROGRESS (or the replayed
    result once it's done). The card is charged once.""")
def s4():
    user = user_pool(1)[0]
    status, body, _ = hold(user, [seat("C2")])
    booking, key = body["bookingId"], f"pay-{RUN}"
    print_example("POST", f"/bookings/{booking}/pay", user, {"paymentToken": "tok_visa"}, {"Idempotency-Key": key},
                  prefix="× 5")
    results = fire_together([lambda: pay(user, booking, key) for _ in range(5)])
    print()
    tally(results)
    sql(f"SELECT id, status, amount, idempotency_key, gateway_ref FROM payment WHERE booking_id = {booking}",
        "payments for the booking (must be one)")
    check(sql_value(f"SELECT count(*) FROM payment WHERE booking_id = {booking}") == "1", "charged exactly once")


@scenario("Last use of a discount code, 10 buyers", """
    Admin creates a code with usage_limit 1. Ten customers lock a seat each with the code (a lock only validates
    it), then all ten pay at the same instant. The conditional UPDATE on discount_code lets one through;
    the other nine get 422 INVALID_DISCOUNT before their card is touched.""")
def s5():
    code = f"LAST{RUN.upper()}"
    now = datetime.now(IST)
    http("POST", "/admin/discounts", auth=ADMIN, body={
        "code": code, "type": "FLAT", "value": 50, "minOrder": 0, "usageLimit": 1, "perUserLimit": 1,
        "validFrom": (now - timedelta(days=1)).isoformat(), "validTo": (now + timedelta(days=30)).isoformat()})
    ok(f"code {code}: ₹50 off, usage_limit 1")
    users = user_pool(10)
    bookings = [hold(u, [seat(f"D{i + 1}")], code)[1]["bookingId"] for i, u in enumerate(users)]
    ok(f"10 bookings HELD with {code}: {bookings[0]} … {bookings[-1]}")
    print_example("POST", f"/bookings/{bookings[0]}/pay", users[0], {"paymentToken": "tok_visa"},
                  {"Idempotency-Key": "<unique per customer>"}, prefix="× 10 (one per customer)")
    results = fire_together([lambda u=u, b=b: pay(u, b) for u, b in zip(users, bookings)])
    print()
    counts = tally(results)
    check(counts.get("200", 0) == 1, "exactly one customer got the discount")
    sql(f"SELECT d.code, d.usage_limit, d.used_count, "
        f"(SELECT count(*) FROM booking b WHERE b.discount_code_id = d.id AND b.discount_redeemed) AS bookings_redeemed, "
        f"(SELECT count(*) FROM payment p JOIN booking b ON b.id = p.booking_id WHERE b.discount_code_id = d.id) AS charges "
        f"FROM discount_code d WHERE d.code = '{code}'", "the code (used once, charged once)")


@scenario("Rate limit", """
    Each customer may send 10 lock requests per minute (5 payments, 100 other calls). One customer fires 12 lock
    requests in a row: 10 reach the app (and fail validation, the body is empty), 2 get 429 with Retry-After.
    The counter is a Redis key per customer, endpoint and minute.""")
def s6():
    user = user_pool(1)[0]
    if not wait_for_redis_breaker(user):
        bad("Redis circuit breaker is still open (rate limits fail open); try this scenario again in a moment")
        return
    print_example("POST", "/bookings", user, {}, prefix="× 12, one after another")
    results = [http("POST", "/bookings", auth=user, body={}) for _ in range(12)]
    print()
    counts = tally(results)
    limited = [h for s, _, h in results if s == 429]
    check(counts.get("429 RATE_LIMITED", 0) == 2, "2 requests rate-limited")
    if limited:
        note(f"Retry-After: {limited[0].get('Retry-After')} s")
    _, me, _ = http("GET", "/auth/me", auth=user)
    keys = docker("exec", "mtbs-redis", "redis-cli", "--scan", "--pattern", f"rl:{me.get('id')}:*").stdout.split()
    for k in keys:
        v = docker("exec", "mtbs-redis", "redis-cli", "GET", k).stdout.strip()
        ttl = docker("exec", "mtbs-redis", "redis-cli", "TTL", k).stdout.strip()
        print(f"  │ Redis {k} → {bold(v)} (ttl {ttl}s)")


@scenario("Lock expires, someone else takes the seat, the late payment is refused", """
    Customer X locks C3 and walks away. We fast-forward 10 minutes (move the Postgres times into the past and drop
    the Redis key, exactly what the clock and the TTL would do). Y locks C3 without any cleanup job.
    X finally presses Pay: 409 HOLD_EXPIRED, and no payment row is created (X is not charged).""")
def s7():
    x, y = user_pool(2)
    status, body, _ = hold(x, [seat("C3")])
    xb = body["bookingId"]
    ok(f"X holds C3 (booking {xb})")
    sql_value(f"UPDATE seat_lock SET held_until = now() - interval '1 second' WHERE booking_id = {xb}; "
              f"UPDATE booking SET hold_expires_at = now() - interval '1 second' WHERE id = {xb}")
    docker("exec", "mtbs-redis", "redis-cli", "DEL", f"lock:{{show:{ctx['show']}}}:seat:{seat('C3')}",
           f"seatmap:{ctx['show']}")
    ok("fast-forwarded 10 minutes: Postgres times in the past, Redis key deleted")
    status, body, _ = hold(y, [seat("C3")])
    check(status == 201, f"Y locks C3 → {status} (booking {body.get('bookingId') if body else None})")
    status, body, _ = pay(x, xb)
    check(status == 409 and body["code"] == "HOLD_EXPIRED", f"X pays → {status} {body.get('code')}")
    sql(f"SELECT b.id, b.status, (SELECT count(*) FROM payment p WHERE p.booking_id = b.id) AS payments "
        f"FROM booking b WHERE b.id = {xb}", "X's booking: EXPIRED, no payment")
    sql(f"SELECT booking_id, booked, held_until FROM seat_lock WHERE show_id = {ctx['show']} AND seat_id = {seat('C3')}",
        "C3 now belongs to Y")


@scenario("Redis goes down in the middle of a sale", """
    docker stop mtbs-redis. Browsing keeps working (caches fail open to Postgres), the circuit breaker skips the
    Redis filter, and 20 customers racing for C4 still produce exactly one winner, because Postgres decides.
    Redis is started again at the end.""")
def s8():
    users = user_pool(20)
    print(blue("\n  $ docker stop mtbs-redis"))
    docker("stop", "mtbs-redis")
    try:
        status, _, _ = http("GET", f"/movies/3/shows?cityId=1&date={(datetime.now(IST) + timedelta(days=5)).date()}")
        check(status == 200, f"browsing still works → {status}")
        results = fire_together([lambda u=u: hold(u, [seat("C4")]) for u in users])
        print()
        counts = tally(results)
        check(counts.get("201", 0) == 1, "exactly one winner without Redis")
        sql(f"SELECT booking_id, seat_id, held_until FROM seat_lock WHERE show_id = {ctx['show']} "
            f"AND seat_id = {seat('C4')}", "seat_lock for C4")
        note("app log: docker compose logs app | grep -i redis   (fallback warnings, breaker OPEN)")
    finally:
        print(blue("\n  $ docker start mtbs-redis"))
        docker("start", "mtbs-redis")
        time.sleep(3)
        ok("Redis is back")


@scenario("Kafka is paused", """
    docker pause mtbs-kafka. A customer books and pays: CONFIRMED as usual, because the booking only writes an outbox
    row. The publisher can't reach Kafka, so the rows stay NOT_STARTED with attempts and last_error growing.
    After docker unpause the same rows go IN_QUEUE → PROCESSED and the notification is sent once.""")
def s9():
    user = user_pool(1)[0]
    print(blue("\n  $ docker pause mtbs-kafka"))
    docker("pause", "mtbs-kafka")
    try:
        status, body, _ = hold(user, [seat("C5")])
        booking = body["bookingId"]
        status, body, _ = pay(user, booking)
        check(status == 200 and body["status"] == "CONFIRMED", f"booking {booking} CONFIRMED while Kafka is down")
        note("waiting 10 s for the publisher to try…")
        time.sleep(10)
        sql(f"SELECT id, event_type, status, attempts, left(last_error, 50) AS last_error, next_attempt_at "
            f"FROM outbox_event WHERE aggregate_id = {booking} ORDER BY id", "outbox while Kafka is down")
    finally:
        print(blue("\n  $ docker unpause mtbs-kafka"))
        docker("unpause", "mtbs-kafka")
    deadline = time.time() + 90
    while time.time() < deadline:
        state = sql_value(f"SELECT status FROM outbox_event WHERE aggregate_id = {booking} "
                          f"AND event_type = 'BOOKING_CONFIRMED'")
        if state == "PROCESSED":
            break
        time.sleep(2)
    check(state == "PROCESSED", "BOOKING_CONFIRMED delivered after Kafka came back", f"still {state} after 90 s")
    sql(f"SELECT id, event_type, status, attempts, sent_at, processed_at FROM outbox_event "
        f"WHERE aggregate_id = {booking} ORDER BY id", "outbox after Kafka is back")


def main():
    require_stack()
    banner("Movie Ticket Booking: concurrency, limits and failure drills",
           f"{len(SCENARIOS)} scenarios · Enter runs the next one · s skips · q quits · run id {RUN}")
    step_header(0, len(SCENARIOS), "Setup", "A fresh screen and show for this run, and 560 test customers.")
    wait_enter("Press Enter to set up  (q = quit) ")
    setup()
    for n, (title, why, fn) in enumerate(SCENARIOS, 1):
        step_header(n, len(SCENARIOS), title, why)
        if wait_enter():
            fn()
    banner("Done", f"show {ctx['show']} · final seat map below")
    _, seat_map, _ = http("GET", f"/shows/{ctx['show']}/seats")
    seat_grid(seat_map)


if __name__ == "__main__":
    main()
