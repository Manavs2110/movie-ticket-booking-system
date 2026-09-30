#!/usr/bin/env python3
"""One customer's whole journey, one curl per Enter, with Postgres / Redis / app-log peeks after the key steps.

    python3 scripts/demo/lifecycle.py              (add --no-peek to show only the API calls)

register → login → browse a city → find theaters near me showing a movie → pick a show → seat map → price preview →
lock seats → a rival is rejected → pay → replay the payment → notifications via Kafka → history → cancel with refund.
Every run registers new customers, so it can be run again and again on the same database.
"""
import sys
import time
import uuid
from datetime import datetime, timedelta

sys.path.insert(0, __import__("os").path.dirname(__file__))
from common import (ADMIN, IST, PASSWORD, app_log, banner, bold, check, cyan, dim, green, ist, note, redis_locks,
                    require_stack, run_curl, seat_grid, sql, step_header, wait_enter, yellow)

RUN = uuid.uuid4().hex[:6]
RIYA = (f"riya.{RUN}@demo.com", PASSWORD)
ARJUN = (f"arjun.{RUN}@demo.com", PASSWORD)
HOME = (13.0035, 77.5647)                          # Riya lives in Malleshwaram, Bengaluru
DAY = (datetime.now(IST) + timedelta(days=3)).date().isoformat()   # > 24 h ahead → 100 % refund on cancel

ctx = {}
STEPS = []


def step(title, why):
    def register(fn):
        STEPS.append((title, why, fn))
        return fn
    return register


# ---------------------------------------------------------------- the journey

@step("Register a customer", f"""
    Riya signs up. The password is stored as a BCrypt hash; the role is always CUSTOMER
    (even if the request tries to send "role": "ADMIN").  Email: {RIYA[0]}""")
def register():
    r = run_curl("POST", "/auth/register",
                 body={"email": RIYA[0], "password": PASSWORD, "fullName": "Riya Sharma"})
    check(r.status == 201 and r.json["role"] == "CUSTOMER", "201 Created, role CUSTOMER")
    sql(f"SELECT id, email, full_name, role, left(password_hash, 20) || '…' AS password_hash "
        f"FROM app_user WHERE email = '{RIYA[0]}'", "the new row (password is a hash)")


@step("Log in", """
    The API is stateless: every request carries HTTP Basic credentials (no session, no cookie).
    /auth/me is the "login" check: right password → who you are; wrong password → 401.""")
def login():
    r = run_curl("GET", "/auth/me", auth=RIYA)
    check(r.status == 200, f"logged in as {r.json and r.json.get('email')}")
    r = run_curl("GET", "/auth/me", auth=(RIYA[0], "wrong-password"))
    check(r.status == 401, "wrong password → 401 UNAUTHORIZED")


@step("Pick a city", "Public endpoint (no login). Cached in Redis for 1 h under mtbs::cities::all.")
def cities():
    r = run_curl("GET", "/cities")
    ctx["city"] = next(c for c in r.json if c["name"] == "Bengaluru")
    check(True, f"Bengaluru → cityId {ctx['city']['id']}")


@step("Movies playing in the city", f"""
    Movies with at least one scheduled show in Bengaluru on {DAY}, optionally filtered by language / genre.""")
def movies():
    r = run_curl("GET", f"/cities/{ctx['city']['id']}/movies?date={DAY}", max_lines=25)
    ctx["movie"] = r.json["items"][0]
    check(True, f"picked \"{ctx['movie']['title']}\" (movieId {ctx['movie']['id']}, {ctx['movie']['language']})")


@step("Theaters near me showing that movie", f"""
    Riya shares her location ({HOME[0]}, {HOME[1]}). Theaters come back nearest first, each with its
    showtimes for the day and the prices set on each show (regular / premium, × weekend multiplier).""")
def theaters():
    r = run_curl("GET", f"/movies/{ctx['movie']['id']}/shows?cityId={ctx['city']['id']}&date={DAY}"
                        f"&lat={HOME[0]}&lng={HOME[1]}", print_body=False)
    for t in r.json["items"]:
        times = ", ".join(ist(s["startTime"])[-9:-4] for s in t["showtimes"])
        print(f"   {bold(t['name']):<40} {t['distanceKm']:>5.1f} km   {dim(times)}")
    theater = r.json["items"][0]
    show = theater["showtimes"][-1]                       # the evening show at the nearest theater
    ctx.update(theater=theater, show_id=show["showId"])
    check(True, f"nearest: {theater['name']} → show {show['showId']} at {ist(show['startTime'])} "
                f"(₹{show['regularPrice']} regular / ₹{show['premiumPrice']} premium)")


@step("Show details", "Movie, screen, start/end time and the prices that live on the show.")
def show_details():
    r = run_curl("GET", f"/shows/{ctx['show_id']}")
    check(r.status == 200, f"{r.json['movieTitle']} · {r.json['theaterName']} · {r.json['screenName']}")


@step("Seat map", """
    Live availability for the show. The response has an ETag: the app polls with If-None-Match and gets a cheap
    304 until something changes. Built from Postgres, kept 2 s in Redis (seatmap:<showId>).""")
def seat_map():
    r = run_curl("GET", f"/shows/{ctx['show_id']}/seats", print_body=False)
    seats = r.json["seats"]
    regular = next(s for s in seats if s["state"] == "AVAILABLE" and s["type"] == "REGULAR")
    premium = next(s for s in seats if s["state"] == "AVAILABLE" and s["type"] == "PREMIUM")
    ctx.update(seats=[regular["seatId"], premium["seatId"]], labels=[regular["label"], premium["label"]],
               premium=premium["seatId"], etag=r.headers.get("etag"))
    seat_grid(r.json, highlight=ctx["seats"])
    check(True, f"Riya picks {regular['label']} (₹{regular['price']}) and {premium['label']} (₹{premium['price']})")
    r = run_curl("GET", f"/shows/{ctx['show_id']}/seats", headers={"If-None-Match": ctx["etag"]}, print_body=False)
    check(r.status == 304, "polling again with the ETag → 304 Not Modified (no body)")


@step("Price preview with a discount code", """
    Nothing is locked and the code isn't used up yet: FIRST50 is only validated here (₹50 off, once per customer).""")
def preview():
    r = run_curl("POST", "/discounts/preview", auth=RIYA,
                 body={"code": "FIRST50", "showId": ctx["show_id"], "seatIds": ctx["seats"]})
    check(r.status == 200, f"subtotal ₹{r.json['subtotal']} − ₹{r.json['discount']} = ₹{r.json['total']}")


@step("Lock the seats", """
    Two layers: Redis SET NX on each seat (fast filter, TTL), then the Postgres claim in seat_lock (the owner).
    All or nothing. The lock lasts 10 minutes; nobody else can take these seats meanwhile.""")
def hold():
    r = run_curl("POST", "/bookings", auth=RIYA,
                 body={"showId": ctx["show_id"], "seatIds": ctx["seats"], "discountCode": "FIRST50"})
    ok = check(r.status == 201, f"booking {r.json and r.json.get('bookingId')} HELD until "
                                f"{r.json and ist(r.json['holdExpiresAt'])}, total ₹{r.json and r.json.get('total')}")
    if not ok:
        sys.exit(1)
    ctx["booking"] = r.json["bookingId"]
    sql(f"SELECT sl.booking_id, s.row_label || s.seat_number AS seat, sl.booked, "
        f"round(extract(epoch FROM sl.held_until - now())) AS seconds_left "
        f"FROM seat_lock sl JOIN seat s ON s.id = sl.seat_id WHERE sl.booking_id = {ctx['booking']}",
        "seat_lock rows (the owner)")
    redis_locks(ctx["show_id"])
    sql(f"SELECT id, status, subtotal, discount_amount, total_amount, hold_expires_at FROM booking "
        f"WHERE id = {ctx['booking']}", "booking")


@step("A rival tries to grab the same seat", f"""
    Arjun (a second customer) asks for {{premium}} plus a free seat. Redis already has {{premium}}, so he is turned
    away in memory without touching Postgres, and the free seat is NOT locked either (all or nothing).""")
def rival():
    run_curl("POST", "/auth/register", body={"email": ARJUN[0], "password": PASSWORD, "fullName": "Arjun Mehta"},
             show=False)
    seats = run_curl("GET", f"/shows/{ctx['show_id']}/seats", show=False).json["seats"]
    free = next(s["seatId"] for s in seats if s["state"] == "AVAILABLE" and s["seatId"] not in ctx["seats"])
    r = run_curl("POST", "/bookings", auth=ARJUN, body={"showId": ctx["show_id"], "seatIds": [ctx["premium"], free]})
    check(r.status == 409 and r.json["code"] == "SEATS_UNAVAILABLE",
          f"409 SEATS_UNAVAILABLE, unavailableSeatIds = {r.json and r.json.get('details', {}).get('unavailableSeatIds')}")
    redis_locks(ctx["show_id"], "still only Riya's keys (Arjun's free seat was not locked)")


@step("Pay", """
    The Idempotency-Key makes the payment safe to retry. Postgres extends the lock for the payment window, the mock
    gateway is charged with NO transaction open, then one transaction confirms: seat_lock.booked = true,
    booking CONFIRMED, payment SUCCESS, events written to the outbox. After commit Redis keys become BOOKED.""")
def pay():
    ctx["key"] = f"riya-pay-{RUN}"
    r = run_curl("POST", f"/bookings/{ctx['booking']}/pay", auth=RIYA,
                 headers={"Idempotency-Key": ctx["key"]}, body={"paymentToken": "tok_visa"})
    check(r.status == 200 and r.json["status"] == "CONFIRMED", f"CONFIRMED, paid ₹{r.json and r.json.get('total')}")
    sql(f"SELECT s.row_label || s.seat_number AS seat, sl.booked, sl.held_until FROM seat_lock sl "
        f"JOIN seat s ON s.id = sl.seat_id WHERE sl.booking_id = {ctx['booking']}",
        "seat_lock: booked = true, held until the show ends")
    redis_locks(ctx["show_id"], "keys now say BOOKED (kept until the show ends)")
    sql(f"SELECT id, status, amount, idempotency_key, gateway_ref FROM payment WHERE booking_id = {ctx['booking']}",
        "payment")
    sql(f"SELECT b.id, b.discount_redeemed, d.code, d.used_count FROM booking b JOIN discount_code d "
        f"ON d.id = b.discount_code_id WHERE b.id = {ctx['booking']}", "discount counted at payment")


@step("Pay again with the same key (double click / network retry)", """
    Same Idempotency-Key → the same result comes back and the card is NOT charged twice.""")
def replay():
    r = run_curl("POST", f"/bookings/{ctx['booking']}/pay", auth=RIYA,
                 headers={"Idempotency-Key": ctx["key"]}, body={"paymentToken": "tok_visa"}, print_body=False)
    check(r.status == 200 and r.json["status"] == "CONFIRMED", "200, still CONFIRMED")
    sql(f"SELECT count(*) AS payments_for_booking FROM payment WHERE booking_id = {ctx['booking']}",
        "still exactly one payment")


@step("Notifications: outbox → Kafka → consumer", """
    The confirm transaction wrote the events into outbox_event. The publisher (every 2 s) sends them to Kafka
    (IN_QUEUE); the notification consumer marks them PROCESSED and "sends" the email/SMS (logged as [notify]).
    The SHOW_REMINDER waits as NOT_STARTED until 2 h before the show.""")
def events():
    time.sleep(3)
    r = run_curl("GET", f"/admin/outbox?bookingId={ctx['booking']}", auth=ADMIN, print_body=False)
    for e in r.json:
        colour = green if e["status"] == "PROCESSED" else yellow
        print(f"   #{e['id']:<5} {e['eventType']:<18} {colour(e['status']):<20} due {ist(e['nextAttemptAt'])}")
    app_log(f"to={RIYA[0]}", title="app log: notifications sent to Riya")
    note("Kafka UI: http://localhost:8082 → Topics → booking-events → Messages")


@step("Booking history", "Riya's bookings, newest first, cursor-paginated (pass nextCursor back as ?cursor=).")
def history():
    r = run_curl("GET", "/me/bookings?size=5", auth=RIYA, max_lines=30)
    check(r.status == 200, f"{len(r.json['items'])} booking(s)")


@step("Cancel and get a refund", """
    The show is more than 24 h away, so the Standard policy refunds 100 %. The seats are released in both layers,
    the refund is recorded on the booking, and the pending reminder is cancelled.""")
def cancel():
    r = run_curl("POST", f"/bookings/{ctx['booking']}/cancel", auth=RIYA)
    refund = (r.json or {}).get("refund") or {}
    check(r.status == 200 and r.json["status"] == "CANCELLED",
          f"CANCELLED, refund ₹{refund.get('amount')} ({refund.get('percentage')} %, {refund.get('status')})")
    sql(f"SELECT count(*) AS seat_lock_rows FROM seat_lock WHERE booking_id = {ctx['booking']}", "seats released")
    redis_locks(ctx["show_id"], "Redis keys gone")
    sql(f"SELECT id, status, refund_amount, refund_percent, refund_reason, refund_status, refunded_at "
        f"FROM booking WHERE id = {ctx['booking']}", "refund lives on the booking")
    sql(f"SELECT status FROM payment WHERE booking_id = {ctx['booking']}", "payment")


@step("Final event log", "Every event for this booking, end to end.")
def final_events():
    time.sleep(3)
    sql(f"SELECT id, event_type, status, attempts, sent_at, processed_at FROM outbox_event "
        f"WHERE aggregate_id = {ctx['booking']} ORDER BY id", "outbox_event")
    app_log(f"to={RIYA[0]}", title="app log: notifications sent to Riya")


def main():
    require_stack()
    banner("Movie Ticket Booking: one customer's journey",
           f"{len(STEPS)} steps · Enter runs the next call · s skips · q quits · run id {RUN}")
    print(dim("  Handy windows: Adminer http://localhost:8081 · RedisInsight http://localhost:5540 · "
              "Kafka UI http://localhost:8082 · ./scripts/watch-locks.sh <showId>"))
    for n, (title, why, fn) in enumerate(STEPS, 1):
        why = why.replace("{premium}", ctx.get("labels", ["", "the premium seat"])[1])
        step_header(n, len(STEPS), title, why)
        if wait_enter():
            fn()
    banner("Done", f"booking {ctx.get('booking')} on show {ctx.get('show_id')} · customers {RIYA[0]}, {ARJUN[0]}")


if __name__ == "__main__":
    main()
