#!/usr/bin/env python3
"""Generates docs/postman/MovieBooking-Lifecycle.postman_collection.json: one customer's journey, the same story as
scripts/demo/lifecycle.py, as a Postman collection.

Everything is literal (full URLs, ids, logins, bodies; no variables), so it needs a FRESH database and the requests
run in order: docker compose down -v && docker compose up -d --build
    python3 scripts/build-lifecycle-postman.py

The admin schedules our own show on a fixed date (Fri 18 Dec 2026, outside the 14 days of seeded shows), so the
customer's search finds exactly that show and every id below is known in advance:
screen 7 (seats A1-A10 = 721-730 regular, B1-B10 = 731-740 premium), show 337, Riya's booking 1.
"""
import json
import os
import uuid

BASE = "http://localhost:8080/api"
ADMIN = ("admin@moviebooking.com", "Admin@123")
RIYA = ("riya@test.com", "Password@123")
ARJUN = ("arjun@test.com", "Password@123")
DAY = "2026-12-18"                        # a Friday: weekday prices
HOME = "lat=13.0035&lng=77.5647"          # Malleshwaram, Bengaluru
SHOW, SCREEN, BOOKING = 337, 7, 1
A1, A2, B1, B2 = 721, 722, 731, 732
ETAG = '"L1-S0-d41d8cd98f00b204e9800998ecf8427e"'


def step(name, method, path, *, auth=None, body=None, headers=None, status, checks="", pre="", desc=""):
    return dict(name=name, method=method, path=path, auth=auth, body=body, headers=headers or {}, status=status,
                checks=checks, pre=pre, desc=desc)


def err(code):
    return f'pm.test("error code {code}", () => pm.expect(pm.response.json().code).to.eql("{code}"));'


def test(title, body):
    return f'pm.test("{title}", () => {{ {body} }});'


WAIT = "// give the outbox publisher (every 2 s) and the Kafka consumer time to catch up\nsetTimeout(() => {}, 4000);"

FOLDERS = [
    ("1 · Sign up and log in", [
        step("1. Register Riya", "POST", "/auth/register", status=201,
             body={"email": RIYA[0], "password": RIYA[1], "fullName": "Riya Sharma"},
             checks=test("CUSTOMER", 'pm.expect(pm.response.json().role).to.eql("CUSTOMER");'),
             desc="Password stored as a BCrypt hash; the role is always CUSTOMER."),
        step("2. Log in (who am I)", "GET", "/auth/me", auth=RIYA, status=200,
             checks=test("Riya", f'pm.expect(pm.response.json().email).to.eql("{RIYA[0]}");'),
             desc="Stateless HTTP Basic: every request carries the credentials, no session or cookie."),
        step("3. Wrong password", "GET", "/auth/me", auth=(RIYA[0], "wrong-password"), status=401,
             checks=err("UNAUTHORIZED")),
    ]),
    ("2 · Admin schedules a show", [
        step("4. Create a screen at PVR Orion Mall (row A regular, row B premium)", "POST", "/admin/theaters/1/screens",
             auth=ADMIN, status=201,
             body={"name": "Lifecycle Screen", "rows": [{"rowLabel": "A", "seatCount": 10, "seatType": "REGULAR"},
                                                        {"rowLabel": "B", "seatCount": 10, "seatType": "PREMIUM"}]},
             checks=test(f"screen {SCREEN}, 20 seats",
                         f'pm.expect(pm.response.json().id).to.eql({SCREEN}); pm.expect(pm.response.json().seatCount).to.eql(20);')),
        step("5. Schedule Chai & Chaos, Fri 18 Dec 2026 19:00 IST, ₹200 / ₹300", "POST", "/admin/shows", auth=ADMIN,
             status=201,
             body={"movieId": 3, "screenId": SCREEN, "startTime": f"{DAY}T19:00:00+05:30",
                   "regularPrice": 200, "premiumPrice": 300},
             checks=test(f"show {SHOW}, SCHEDULED", f'pm.expect(pm.response.json().id).to.eql({SHOW}); '
                                                   'pm.expect(pm.response.json().status).to.eql("SCHEDULED");')),
    ]),
    ("3 · Find a show", [
        step("6. Cities", "GET", "/cities", status=200,
             checks=test("Bengaluru is city 1", 'pm.expect(pm.response.json().find(c => c.name === "Bengaluru").id).to.eql(1);'),
             desc="Public, no login. Cached in Redis for 1 h (mtbs::cities::all)."),
        step(f"7. Movies in Bengaluru on {DAY}", "GET", f"/cities/1/movies?date={DAY}", status=200,
             checks=test("Chai & Chaos is playing",
                         'pm.expect(pm.response.json().items.map(m => m.title)).to.include("Chai & Chaos");')),
        step("8. Theaters near me showing Chai & Chaos", "GET", f"/movies/3/shows?cityId=1&date={DAY}&{HOME}",
             status=200,
             checks=test(f"PVR Orion Mall with show {SHOW}",
                         'const t = pm.response.json().items[0]; pm.expect(t.name).to.eql("PVR Orion Mall"); '
                         f'pm.expect(t.showtimes.map(s => s.showId)).to.include({SHOW}); pm.expect(t.distanceKm).to.be.below(5);'),
             desc="Nearest theater first, each with its showtimes and the prices set on each show."),
        step("9. Show details", "GET", f"/shows/{SHOW}", status=200,
             checks=test("₹200 / ₹300 / ×1.25", 'const s = pm.response.json(); pm.expect(s.regularPrice).to.eql(200); '
                                               'pm.expect(s.premiumPrice).to.eql(300); pm.expect(s.weekendMultiplier).to.eql(1.25);')),
        step("10. Seat map", "GET", f"/shows/{SHOW}/seats", status=200,
             checks=test("20 seats available, A1 ₹200, B1 ₹300",
                         'const s = pm.response.json().seats; pm.expect(s.filter(x => x.state === "AVAILABLE").length).to.eql(20); '
                         'pm.expect(s[0].price).to.eql(200); pm.expect(s[10].price).to.eql(300);')
                    + "\n" + test("ETag", 'pm.response.to.have.header("ETag");'),
             desc="Live availability; poll with If-None-Match to get a cheap 304 until something changes."),
        step("11. Seat map again with If-None-Match", "GET", f"/shows/{SHOW}/seats", headers={"If-None-Match": ETAG},
             status=304, desc="Nothing changed → 304 Not Modified, no body."),
        step("12. Price preview for A1 + B1 with FIRST50", "POST", "/discounts/preview", auth=RIYA, status=200,
             body={"code": "FIRST50", "showId": SHOW, "seatIds": [A1, B1]},
             checks=test("500 − 50 = 450", 'const p = pm.response.json(); pm.expect(p.subtotal).to.eql(500); '
                                          'pm.expect(p.discount).to.eql(50); pm.expect(p.total).to.eql(450);'),
             desc="Validates the code only: nothing is locked and the code isn't counted yet."),
    ]),
    ("4 · Lock seats", [
        step("13. Riya locks A1 + B1", "POST", "/bookings", auth=RIYA, status=201,
             body={"showId": SHOW, "seatIds": [A1, B1], "discountCode": "FIRST50"},
             checks=test(f"booking {BOOKING} HELD, total 450",
                         f'const b = pm.response.json(); pm.expect(b.bookingId).to.eql({BOOKING}); '
                         'pm.expect(b.status).to.eql("HELD"); pm.expect(b.total).to.eql(450);'),
             desc="Redis SET NX filter + Postgres seat_lock claim, all or nothing, 10 minutes.\n\n"
                  "Look now: Adminer → seat_lock (2 rows), RedisInsight → lock:{show:337}:* (value 1, TTL ≈ 600 s)."),
        step("14. Register Arjun", "POST", "/auth/register", status=201,
             body={"email": ARJUN[0], "password": ARJUN[1], "fullName": "Arjun Mehta"}),
        step("15. Arjun tries B1 + B2 (B1 is Riya's)", "POST", "/bookings", auth=ARJUN, status=409,
             body={"showId": SHOW, "seatIds": [B1, B2]},
             checks=err("SEATS_UNAVAILABLE") + "\n" +
                    test("only B1 reported", f'pm.expect(pm.response.json().details.unavailableSeatIds).to.eql([{B1}]);'),
             desc="Rejected by Redis in memory; B2 is not locked either (all or nothing)."),
        step("16. Seat map now", "GET", f"/shows/{SHOW}/seats", status=200,
             checks=test("A1 and B1 HELD, B2 still free",
                         'const s = pm.response.json().seats; pm.expect(s[0].state).to.eql("HELD"); '
                         'pm.expect(s[10].state).to.eql("HELD"); pm.expect(s[11].state).to.eql("AVAILABLE");')),
    ]),
    ("5 · Pay", [
        step("17. Riya pays", "POST", f"/bookings/{BOOKING}/pay", auth=RIYA, status=200,
             headers={"Idempotency-Key": "riya-pay-1"}, body={"paymentToken": "tok_visa"},
             checks=test("CONFIRMED", 'pm.expect(pm.response.json().status).to.eql("CONFIRMED");'),
             desc="Gateway charged with no DB transaction open; then one transaction confirms the seats, the "
                  "booking, the payment and writes the events. Redis values become BOOKED."),
        step("18. Same payment again (double click)", "POST", f"/bookings/{BOOKING}/pay", auth=RIYA, status=200,
             headers={"Idempotency-Key": "riya-pay-1"}, body={"paymentToken": "tok_visa"},
             checks=test("same result, no second charge", 'pm.expect(pm.response.json().status).to.eql("CONFIRMED");')),
        step("19. Riya's booking", "GET", f"/bookings/{BOOKING}", auth=RIYA, status=200,
             checks=test("CONFIRMED, A1 + B1, FIRST50",
                         'const b = pm.response.json(); pm.expect(b.status).to.eql("CONFIRMED"); '
                         'pm.expect(b.items.map(i => i.seat)).to.eql(["A1", "B1"]); pm.expect(b.discountCode).to.eql("FIRST50");')),
        step("20. Events for the booking (outbox → Kafka → consumer)", "GET", f"/admin/outbox?bookingId={BOOKING}",
             auth=ADMIN, status=200, pre=WAIT,
             checks=test("confirmation PROCESSED, reminder waiting",
                         'const e = Object.fromEntries(pm.response.json().map(x => [x.eventType, x.status])); '
                         'pm.expect(e.BOOKING_CONFIRMED).to.eql("PROCESSED"); pm.expect(e.SHOW_REMINDER).to.eql("NOT_STARTED");'),
             desc="Waits 4 s first. BOOKING_CONFIRMED went NOT_STARTED → IN_QUEUE → PROCESSED; SHOW_REMINDER waits "
                  "until 2 h before the show. Kafka UI: http://localhost:8082 → booking-events."),
        step("21. Riya's booking history", "GET", "/me/bookings?size=5", auth=RIYA, status=200,
             checks=test("booking 1 listed", f'pm.expect(pm.response.json().items.map(b => b.bookingId)).to.include({BOOKING});')),
    ]),
    ("6 · Cancel and refund", [
        step("22. Riya cancels (show > 24 h away → 100 %)", "POST", f"/bookings/{BOOKING}/cancel", auth=RIYA, status=200,
             checks=test("CANCELLED, ₹450 refunded",
                         'const b = pm.response.json(); pm.expect(b.status).to.eql("CANCELLED"); '
                         'pm.expect(b.refund.amount).to.eql(450); pm.expect(b.refund.percentage).to.eql(100); '
                         'pm.expect(b.refund.status).to.eql("SUCCESS");'),
             desc="Seats released in both layers, refund stored on the booking, reminder cancelled."),
        step("23. Seat map after the cancel", "GET", f"/shows/{SHOW}/seats", status=200,
             checks=test("all 20 seats free again",
                         'pm.expect(pm.response.json().seats.filter(x => x.state === "AVAILABLE").length).to.eql(20);')),
        step("24. Events after the cancel", "GET", f"/admin/outbox?bookingId={BOOKING}", auth=ADMIN, status=200,
             pre=WAIT,
             checks=test("cancel + refund PROCESSED, reminder CANCELLED",
                         'const e = Object.fromEntries(pm.response.json().map(x => [x.eventType, x.status])); '
                         'pm.expect(e.BOOKING_CANCELLED).to.eql("PROCESSED"); pm.expect(e.REFUND_PROCESSED).to.eql("PROCESSED"); '
                         'pm.expect(e.SHOW_REMINDER).to.eql("CANCELLED");')),
        step("25. Refunds (admin)", "GET", "/admin/refunds", auth=ADMIN, status=200,
             checks=test("Riya's refund",
                         f'const r = pm.response.json().find(x => x.bookingId === {BOOKING}); '
                         'pm.expect(r.amount).to.eql(450); pm.expect(r.reason).to.eql("CUSTOMER_CANCEL");')),
    ]),
]


def build():
    folders = []
    for folder, steps in FOLDERS:
        items = []
        for s in steps:
            request = {"method": s["method"], "url": BASE + s["path"],
                       "header": [{"key": k, "value": v} for k, v in s["headers"].items()]}
            request["auth"] = {"type": "basic", "basic": [
                {"key": "username", "value": s["auth"][0], "type": "string"},
                {"key": "password", "value": s["auth"][1], "type": "string"}]} if s["auth"] else {"type": "noauth"}
            if s["body"] is not None:
                request["body"] = {"mode": "raw", "raw": json.dumps(s["body"], indent=2, ensure_ascii=False),
                                   "options": {"raw": {"language": "json"}}}
            if s["desc"]:
                request["description"] = s["desc"]
            checks = [f'pm.test("status {s["status"]}", () => pm.response.to.have.status({s["status"]}));']
            if s["checks"]:
                checks.append(s["checks"])
            events = [{"listen": "test", "script": {"type": "text/javascript", "exec": "\n".join(checks).split("\n")}}]
            if s["pre"]:
                events.insert(0, {"listen": "prerequest", "script": {"type": "text/javascript", "exec": s["pre"].split("\n")}})
            items.append({"name": s["name"], "event": events, "request": request})
        folders.append({"name": folder, "item": items})
    return {
        "info": {
            "_postman_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "moviebooking-lifecycle-collection")),
            "name": "Movie Ticket Booking — Customer Lifecycle",
            "description": (
                "One customer's journey: sign up → log in → find a show near me → seat map → lock → pay → "
                "notifications → cancel with refund (same story as scripts/demo/lifecycle.py).\n\n"
                "Every URL, id, login and body is written out in full: no variables. **Start from a fresh database** "
                "(`docker compose down -v && docker compose up -d --build`) and run the requests in order, then the ids "
                "are exact: screen 7, show 337, seats A1 = 721 … B10 = 740, Riya's booking 1.\n\n"
                "Admin: admin@moviebooking.com / Admin@123 · Customers riya@test.com and arjun@test.com, "
                "password Password@123."),
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "item": folders,
    }


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "docs", "postman", "MovieBooking-Lifecycle.postman_collection.json")
    with open(out, "w") as f:
        json.dump(build(), f, indent=2, ensure_ascii=False)
    print(f"wrote {out} ({sum(len(s) for _, s in FOLDERS)} requests)")
