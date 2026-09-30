#!/usr/bin/env python3
"""Generates docs/postman/MovieBooking.postman_collection.json (Postman v2.1) from the steps below.

Same flow as docs/test.md, but self-contained: every run uses its own emails and screen, IDs are captured from
responses into collection variables, and every request asserts its expected result.
    python3 scripts/build-postman-collection.py
"""
import json
import os
import uuid

ADMIN = ("admin@moviebooking.com", "Admin@123")
PW = "Password@123"
RIYA = ("{{riyaEmail}}", PW)
ARJUN = ("{{arjunEmail}}", PW)
MEERA = ("{{meeraEmail}}", PW)


def step(name, method, path, *, auth=None, body=None, headers=None, status, checks="", capture="", pre="", desc=""):
    return dict(name=name, method=method, path=path, auth=auth, body=body, headers=headers or {}, status=status,
                checks=checks, capture=capture, pre=pre, desc=desc)


def err(code):
    return f'pm.test("error code {code}", () => pm.expect(pm.response.json().code).to.eql("{code}"));'


FOLDERS = [
    ("1 · Public browsing (no login)", [
        step("1. List cities", "GET", "/cities", status=200,
             pre='pm.collectionVariables.set("runId", Date.now().toString().slice(-8));\n'
                 'pm.collectionVariables.set("riyaEmail", `riya.${pm.collectionVariables.get("runId")}@test.com`);\n'
                 'pm.collectionVariables.set("arjunEmail", `arjun.${pm.collectionVariables.get("runId")}@test.com`);\n'
                 'pm.collectionVariables.set("meeraEmail", `meera.${pm.collectionVariables.get("runId")}@test.com`);',
             checks='pm.test("Bengaluru and Mumbai", () => pm.expect(pm.response.json().map(c => c.name)).to.include.members(["Bengaluru", "Mumbai"]));',
             desc="Also starts a new run: sets a unique runId used in this run's emails and screen name."),
        step("2. Movies in Bengaluru today", "GET", "/cities/1/movies", status=200,
             checks='pm.test("paged list", () => pm.expect(pm.response.json()).to.have.property("items"));'),
        step("3. Movies filtered by language", "GET", "/cities/1/movies?language=Hindi", status=200,
             checks='pm.test("only Hindi", () => pm.response.json().items.forEach(m => pm.expect(m.language).to.eql("Hindi")));'),
        step("4. Movie details", "GET", "/movies/1", status=200,
             checks='pm.test("Starfall with genres", () => { const m = pm.response.json(); pm.expect(m.title).to.eql("Starfall"); pm.expect(m.genres).to.be.an("array"); });'),
    ]),
    ("2 · Registration, login and security", [
        step("5. Register Riya", "POST", "/auth/register", status=201,
             body={"email": "{{riyaEmail}}", "password": PW, "fullName": "Riya Sharma"},
             checks='pm.test("CUSTOMER", () => pm.expect(pm.response.json().role).to.eql("CUSTOMER"));'),
        step("6. Register Arjun", "POST", "/auth/register", status=201,
             body={"email": "{{arjunEmail}}", "password": PW, "fullName": "Arjun Mehta"}),
        step("7. Register Meera (tries to sneak in ADMIN)", "POST", "/auth/register", status=201,
             body={"email": "{{meeraEmail}}", "password": PW, "fullName": "Meera Iyer", "role": "ADMIN"},
             checks='pm.test("role ignored: still CUSTOMER", () => pm.expect(pm.response.json().role).to.eql("CUSTOMER"));'),
        step("8. Who am I (login check)", "GET", "/auth/me", auth=RIYA, status=200,
             checks='pm.test("Riya, CUSTOMER", () => { pm.expect(pm.response.json().email).to.eql(pm.collectionVariables.get("riyaEmail")); pm.expect(pm.response.json().role).to.eql("CUSTOMER"); });'),
        step("9. Wrong password", "GET", "/auth/me", auth=("{{riyaEmail}}", "wrong-password"), status=401,
             checks=err("UNAUTHORIZED")),
        step("10. Customer calls an admin API", "GET", "/admin/refund-policies", auth=RIYA, status=403, checks=err("FORBIDDEN")),
        step("11. Register the same email again", "POST", "/auth/register", status=409,
             body={"email": "{{riyaEmail}}", "password": PW, "fullName": "Riya Again"}, checks=err("EMAIL_TAKEN")),
        step("12. Invalid registration", "POST", "/auth/register", status=400,
             body={"email": "not-an-email", "password": "short"},
             checks=err("VALIDATION_ERROR") + '\npm.test("field errors listed", () => pm.expect(pm.response.json().details).to.have.all.keys("email", "password", "fullName"));'),
    ]),
    ("3 · Admin sets up a screen and a show", [
        step("13. Create a screen (row A regular, row B premium)", "POST", "/admin/theaters/1/screens", auth=ADMIN, status=201,
             body={"name": "QA Screen {{runId}}", "rows": [{"rowLabel": "A", "seatCount": 10, "seatType": "REGULAR"},
                                                          {"rowLabel": "B", "seatCount": 10, "seatType": "PREMIUM"}]},
             checks='pm.test("20 seats", () => pm.expect(pm.response.json().seatCount).to.eql(20));',
             capture='pm.collectionVariables.set("screenId", pm.response.json().id);'),
        step("14. Seat layout", "GET", "/admin/screens/{{screenId}}/seats", auth=ADMIN, status=200,
             checks='pm.test("A1 regular, B1 premium", () => { const s = pm.response.json(); pm.expect(s[0].label).to.eql("A1"); pm.expect(s[10].label).to.eql("B1"); pm.expect(s[10].seatType).to.eql("PREMIUM"); });',
             capture='const s = pm.response.json();\n'
                     '["A1","A2","A3"].forEach((l, i) => pm.collectionVariables.set("seat" + l, s[i].id));\n'
                     '["B1","B2","B3"].forEach((l, i) => pm.collectionVariables.set("seat" + l, s[10 + i].id));'),
        step("15. Schedule a show (Tue 15 Dec 2026 19:00 IST, ₹200 regular / ₹300 premium)", "POST", "/admin/shows", auth=ADMIN, status=201,
             body={"movieId": 3, "screenId": "{{screenId}}", "startTime": "2026-12-15T19:00:00+05:30", "regularPrice": 200, "premiumPrice": 300},
             checks='pm.test("SCHEDULED, default weekend ×1.25", () => { pm.expect(pm.response.json().status).to.eql("SCHEDULED"); pm.expect(pm.response.json().weekendMultiplier).to.eql(1.25); });',
             capture='pm.collectionVariables.set("showId", pm.response.json().id);'),
        step("16. Overlapping show on the same screen", "POST", "/admin/shows", auth=ADMIN, status=409,
             body={"movieId": 3, "screenId": "{{screenId}}", "startTime": "2026-12-15T20:00:00+05:30", "regularPrice": 200, "premiumPrice": 300},
             checks=err("SHOW_OVERLAP")),
        step("17. Show in the past", "POST", "/admin/shows", auth=ADMIN, status=400,
             body={"movieId": 3, "screenId": "{{screenId}}", "startTime": "2020-01-01T19:00:00+05:30", "regularPrice": 200, "premiumPrice": 300},
             checks=err("VALIDATION_ERROR")),
        step("17a. New show listed for the movie", "GET", "/movies/3/shows?cityId=1&date=2026-12-15", status=200,
             checks='pm.test("our show is listed", () => { const ids = pm.response.json().items.flatMap(t => t.showtimes.map(s => s.showId)); pm.expect(ids).to.include(Number(pm.collectionVariables.get("showId"))); });'),
    ]),
    ("4 · Seat map and price preview", [
        step("18. Seat map", "GET", "/shows/{{showId}}/seats", status=200,
             checks='pm.test("20 available seats, ₹200 / ₹300", () => { const m = pm.response.json(); pm.expect(m.seats.filter(s => s.state === "AVAILABLE").length).to.eql(20); pm.expect(m.seats[0].price).to.eql(200); pm.expect(m.seats[10].price).to.eql(300); });\n'
                    'pm.test("ETag and no-store", () => { pm.response.to.have.header("ETag"); pm.expect(pm.response.headers.get("Cache-Control")).to.include("no-store"); });',
             capture='pm.collectionVariables.set("etag", pm.response.headers.get("ETag"));'),
        step("19. Poll again with If-None-Match", "GET", "/shows/{{showId}}/seats", headers={"If-None-Match": "{{etag}}"}, status=304),
        step("20. Price preview with FIRST50", "POST", "/discounts/preview", auth=RIYA, status=200,
             body={"code": "first50", "showId": "{{showId}}", "seatIds": ["{{seatA1}}", "{{seatB1}}"]},
             checks='pm.test("500 − 50 = 450", () => { const p = pm.response.json(); pm.expect(p.subtotal).to.eql(500); pm.expect(p.discount).to.eql(50); pm.expect(p.total).to.eql(450); });'),
    ]),
    ("5 · Lock seats, conflicts, pay", [
        step("21. Riya locks A1 + B1 with FIRST50", "POST", "/bookings", auth=RIYA, status=201,
             body={"showId": "{{showId}}", "seatIds": ["{{seatA1}}", "{{seatB1}}"], "discountCode": "FIRST50"},
             checks='pm.test("HELD, total 450", () => { const b = pm.response.json(); pm.expect(b.status).to.eql("HELD"); pm.expect(b.total).to.eql(450); pm.expect(b.holdExpiresAt).to.be.a("string"); });',
             capture='pm.collectionVariables.set("riyaBooking", pm.response.json().bookingId);'),
        step("22. Arjun tries B1 + B2 (B1 taken)", "POST", "/bookings", auth=ARJUN, status=409,
             body={"showId": "{{showId}}", "seatIds": ["{{seatB1}}", "{{seatB2}}"]},
             checks=err("SEATS_UNAVAILABLE") + '\npm.test("only B1 reported", () => pm.expect(pm.response.json().details.unavailableSeatIds).to.eql([Number(pm.collectionVariables.get("seatB1"))]));'),
        step("23. Riya tries a second lock on the same show", "POST", "/bookings", auth=RIYA, status=409,
             body={"showId": "{{showId}}", "seatIds": ["{{seatA3}}"]}, checks=err("ACTIVE_HOLD_EXISTS")),
        step("24. Duplicate seat ids", "POST", "/bookings", auth=MEERA, status=400,
             body={"showId": "{{showId}}", "seatIds": ["{{seatA2}}", "{{seatA2}}"]}, checks=err("INVALID_SEATS")),
        step("25. Seat map with the old ETag", "GET", "/shows/{{showId}}/seats", headers={"If-None-Match": "{{etag}}"}, status=200,
             checks='pm.test("A1 and B1 HELD", () => { const s = pm.response.json().seats; pm.expect(s[0].state).to.eql("HELD"); pm.expect(s[10].state).to.eql("HELD"); pm.expect(s[11].state).to.eql("AVAILABLE"); });'),
        step("26. Riya pays", "POST", "/bookings/{{riyaBooking}}/pay", auth=RIYA, status=200,
             headers={"Idempotency-Key": "riya-pay-{{runId}}"}, body={"paymentToken": "tok_visa"},
             checks='pm.test("CONFIRMED", () => pm.expect(pm.response.json().status).to.eql("CONFIRMED"));'),
        step("27. Same payment again (double-click)", "POST", "/bookings/{{riyaBooking}}/pay", auth=RIYA, status=200,
             headers={"Idempotency-Key": "riya-pay-{{runId}}"}, body={"paymentToken": "tok_visa"},
             checks='pm.test("same result, no second charge", () => pm.expect(pm.response.json().status).to.eql("CONFIRMED"));'),
        step("27a. Pay without Idempotency-Key", "POST", "/bookings/{{riyaBooking}}/pay", auth=RIYA, status=400,
             checks=err("VALIDATION_ERROR")),
    ]),
    ("6 · Declined card and retry", [
        step("28. Arjun locks B2", "POST", "/bookings", auth=ARJUN, status=201,
             body={"showId": "{{showId}}", "seatIds": ["{{seatB2}}"]},
             checks='pm.test("HELD, 300", () => pm.expect(pm.response.json().total).to.eql(300));',
             capture='pm.collectionVariables.set("arjunBooking", pm.response.json().bookingId);'),
        step("29. Declined card", "POST", "/bookings/{{arjunBooking}}/pay", auth=ARJUN, status=402,
             headers={"Idempotency-Key": "arjun-pay-1-{{runId}}"}, body={"paymentToken": "tok_decline"}, checks=err("PAYMENT_FAILED")),
        step("30. Reuse Riya's Idempotency-Key", "POST", "/bookings/{{arjunBooking}}/pay", auth=ARJUN, status=409,
             headers={"Idempotency-Key": "riya-pay-{{runId}}"}, body={"paymentToken": "tok_visa"}, checks=err("IDEMPOTENCY_KEY_REUSED")),
        step("31. Retry with a good card", "POST", "/bookings/{{arjunBooking}}/pay", auth=ARJUN, status=200,
             headers={"Idempotency-Key": "arjun-pay-2-{{runId}}"}, body={"paymentToken": "tok_visa"},
             checks='pm.test("CONFIRMED", () => pm.expect(pm.response.json().status).to.eql("CONFIRMED"));'),
        step("32. Sold seat is rejected", "POST", "/bookings", auth=MEERA, status=409,
             body={"showId": "{{showId}}", "seatIds": ["{{seatB2}}"]}, checks=err("SEATS_UNAVAILABLE")),
    ]),
    ("7 · Discount rules", [
        step("33. FIRST50 a second time", "POST", "/bookings", auth=RIYA, status=422,
             body={"showId": "{{showId}}", "seatIds": ["{{seatA3}}"], "discountCode": "FIRST50"}, checks=err("INVALID_DISCOUNT")),
        step("34. Expired code", "POST", "/discounts/preview", auth=MEERA, status=422,
             body={"code": "EXPIRED10", "showId": "{{showId}}", "seatIds": ["{{seatA3}}"]}, checks=err("INVALID_DISCOUNT")),
        step("35. WEEKEND20 below its ₹300 minimum", "POST", "/discounts/preview", auth=MEERA, status=422,
             body={"code": "WEEKEND20", "showId": "{{showId}}", "seatIds": ["{{seatA3}}"]}, checks=err("INVALID_DISCOUNT")),
    ]),
    ("8 · Cancel and refund", [
        step("36. Meera locks A3", "POST", "/bookings", auth=MEERA, status=201,
             body={"showId": "{{showId}}", "seatIds": ["{{seatA3}}"]},
             capture='pm.collectionVariables.set("meeraBooking", pm.response.json().bookingId);'),
        step("37. Meera cancels before paying", "POST", "/bookings/{{meeraBooking}}/cancel", auth=MEERA, status=200,
             checks='pm.test("CANCELLED, no refund", () => { pm.expect(pm.response.json().status).to.eql("CANCELLED"); pm.expect(pm.response.json()).to.not.have.property("refund"); });'),
        step("38. Riya cancels (show > 24 h away → 100 %)", "POST", "/bookings/{{riyaBooking}}/cancel", auth=RIYA, status=200,
             checks='pm.test("100 % refund of 450", () => { const r = pm.response.json().refund; pm.expect(r.amount).to.eql(450); pm.expect(r.percentage).to.eql(100); pm.expect(r.status).to.eql("SUCCESS"); });'),
        step("39. Cancel again", "POST", "/bookings/{{riyaBooking}}/cancel", auth=RIYA, status=409, checks=err("INVALID_BOOKING_STATE")),
        step("40. Meera looks at Riya's booking", "GET", "/bookings/{{riyaBooking}}", auth=MEERA, status=404, checks=err("BOOKING_NOT_FOUND")),
        step("41. Riya's own booking", "GET", "/bookings/{{riyaBooking}}", auth=RIYA, status=200,
             checks='pm.test("CANCELLED with refund", () => { pm.expect(pm.response.json().status).to.eql("CANCELLED"); pm.expect(pm.response.json().refund.amount).to.eql(450); });'),
        step("42. Riya's booking history", "GET", "/me/bookings?size=5", auth=RIYA, status=200,
             checks='pm.test("has her booking", () => pm.expect(pm.response.json().items.map(b => b.bookingId)).to.include(Number(pm.collectionVariables.get("riyaBooking"))));'),
    ]),
    ("9 · Admin views", [
        step("43. Bookings for the show", "GET", "/admin/shows/{{showId}}/bookings", auth=ADMIN, status=200,
             checks='pm.test("3 bookings", () => pm.expect(pm.response.json().length).to.eql(3));'),
        step("44. Event log (outbox)", "GET", "/admin/outbox?limit=50", auth=ADMIN, status=200,
             checks='pm.test("Riya: confirmed, cancelled, refund events; reminder cancelled", () => { const id = Number(pm.collectionVariables.get("riyaBooking")); const t = pm.response.json().filter(e => e.aggregateId === id).map(e => e.eventType + ":" + (e.eventType === "SHOW_REMINDER" ? e.status : "")); pm.expect(t).to.include.members(["BOOKING_CONFIRMED:", "BOOKING_CANCELLED:", "REFUND_PROCESSED:", "SHOW_REMINDER:CANCELLED"]); });'),
        step("45. Refunds", "GET", "/admin/refunds", auth=ADMIN, status=200),
        step("46. Refund policies", "GET", "/admin/refund-policies", auth=ADMIN, status=200,
             checks='pm.test("Standard is the default", () => pm.expect(pm.response.json().find(p => p.isDefault).name).to.eql("Standard"));'),
        step("47. The show's prices (pricing lives on the show)", "GET", "/shows/{{showId}}", status=200,
             checks='pm.test("200 / 300 / ×1.25", () => { const s = pm.response.json(); pm.expect(s.regularPrice).to.eql(200); pm.expect(s.premiumPrice).to.eql(300); pm.expect(s.weekendMultiplier).to.eql(1.25); });'),
        step("48. Create a discount code", "POST", "/admin/discounts", auth=ADMIN, status=201,
             body={"code": "diwali{{runId}}", "type": "PERCENT", "value": 25, "maxDiscount": 200, "minOrder": 300,
                   "validFrom": "2026-09-01T00:00:00+05:30", "validTo": "2026-12-31T23:59:59+05:30", "usageLimit": 500, "perUserLimit": 2},
             checks='pm.test("stored upper-case", () => pm.expect(pm.response.json().code).to.match(/^DIWALI/));'),
    ]),
    ("10 · Admin cancels the show", [
        step("49. Cancel the show", "POST", "/admin/shows/{{showId}}/cancel", auth=ADMIN, status=200,
             checks='pm.test("newly cancelled, 1 booking refunded", () => { pm.expect(pm.response.json().newlyCancelled).to.eql(true); pm.expect(pm.response.json().bookingsProcessed).to.eql(1); });'),
        step("50. Run it again (idempotent)", "POST", "/admin/shows/{{showId}}/cancel", auth=ADMIN, status=200,
             checks='pm.test("nothing left to do", () => { pm.expect(pm.response.json().newlyCancelled).to.eql(false); pm.expect(pm.response.json().bookingsProcessed).to.eql(0); });'),
        step("51. Arjun's booking now", "GET", "/bookings/{{arjunBooking}}", auth=ARJUN, status=200,
             checks='pm.test("100 % refund, SHOW_CANCELLED", () => { const r = pm.response.json().refund; pm.expect(r.amount).to.eql(300); pm.expect(r.percentage).to.eql(100); pm.expect(r.reason).to.eql("SHOW_CANCELLED"); });'),
        step("52. Lock on the cancelled show", "POST", "/bookings", auth=MEERA, status=409,
             body={"showId": "{{showId}}", "seatIds": ["{{seatA2}}"]}, checks=err("SHOW_NOT_BOOKABLE")),
    ]),
    ("11 · Rate limiting", [
        step("53. 11th lock request in a minute → 429", "POST", "/bookings", auth=MEERA, status=429, body={},
             pre='// fire 10 lock requests first (limit: 10 per user per minute); this request is the one over the limit\n'
                 'const req = { url: "{{baseUrl}}/bookings", method: "POST",\n'
                 '  header: { "Content-Type": "application/json" },\n'
                 '  auth: { type: "basic", basic: [ { key: "username", value: pm.collectionVariables.get("meeraEmail") },\n'
                 '                                   { key: "password", value: "Password@123" } ] },\n'
                 '  body: { mode: "raw", raw: "{}" } };\n'
                 'for (let i = 0; i < 10; i++) pm.sendRequest(req, () => {});',
             checks=err("RATE_LIMITED") + '\npm.test("Retry-After header", () => pm.response.to.have.header("Retry-After"));',
             desc="The pre-request script sends 10 lock requests first; this 11th one is rejected. Wait a minute before running it again."),
    ]),
]


# Values on a FRESH database, requests run in order (6 screens, 336 shows and 720 seats are seeded; every lock
# request takes the next booking number before the seat check, so rejected ones use numbers up too).
LITERAL = {
    "baseUrl": "http://localhost:8080/api", "riyaEmail": "riya@test.com", "arjunEmail": "arjun@test.com",
    "meeraEmail": "meera@test.com", "screenId": 7, "showId": 337, "seatA1": 721, "seatA2": 722, "seatA3": 723,
    "seatB1": 731, "seatB2": 732, "seatB3": 733, "riyaBooking": 1, "arjunBooking": 4, "meeraBooking": 7,
    "etag": '"L1-S0-d41d8cd98f00b204e9800998ecf8427e"',
}
RUN_SPECIFIC = {"QA Screen {{runId}}": "QA Screen", "riya-pay-{{runId}}": "riya-pay-1",
                "arjun-pay-1-{{runId}}": "arjun-pay-1", "arjun-pay-2-{{runId}}": "arjun-pay-2", "diwali{{runId}}": "diwali25"}


def literal(text):
    """Replaces {{var}} and pm.collectionVariables.get("var") with the fresh-database values."""
    import re
    for k, v in RUN_SPECIFIC.items():
        text = text.replace(k, v)
    text = re.sub(r'Number\(pm\.collectionVariables\.get\("(\w+)"\)\)', lambda m: json.dumps(LITERAL[m.group(1)]), text)
    text = re.sub(r'pm\.collectionVariables\.get\("(\w+)"\)', lambda m: json.dumps(LITERAL[m.group(1)]), text)
    text = re.sub(r"\{\{(\w+)\}\}", lambda m: str(LITERAL[m.group(1)]), text)
    return text


def walk(node):
    """Applies literal() to every string in a request / script structure."""
    if isinstance(node, dict):
        return {k: walk(v) for k, v in node.items()}
    if isinstance(node, list):
        return [walk(v) for v in node]
    return literal(node) if isinstance(node, str) else node


def build():
    items = []
    for folder, steps in FOLDERS:
        folder_items = []
        for s in steps:
            headers = [{"key": k, "value": v} for k, v in s["headers"].items()]
            request = {"method": s["method"], "header": headers, "url": "{{baseUrl}}" + s["path"]}
            if s["auth"]:
                request["auth"] = {"type": "basic", "basic": [
                    {"key": "username", "value": s["auth"][0], "type": "string"},
                    {"key": "password", "value": s["auth"][1], "type": "string"}]}
            else:
                request["auth"] = {"type": "noauth"}
            if s["body"] is not None:
                raw = json.dumps(s["body"], indent=2, ensure_ascii=False)
                # numeric variables must not stay quoted strings in JSON bodies
                for var in ("screenId", "showId", "seatA1", "seatA2", "seatA3", "seatB1", "seatB2", "seatB3"):
                    raw = raw.replace('"{{%s}}"' % var, "{{%s}}" % var)
                request["body"] = {"mode": "raw", "raw": raw, "options": {"raw": {"language": "json"}}}
            if s["desc"] and "runId" not in s["desc"]:
                request["description"] = s["desc"]
            test = [f'pm.test("status {s["status"]}", () => pm.response.to.have.status({s["status"]}));']
            if s["checks"]:
                test.append(s["checks"])
            # literal collection: no captured variables needed
            events = [{"listen": "test", "script": {"type": "text/javascript", "exec": "\n".join(test).split("\n")}}]
            if s["pre"] and "runId" not in s["pre"]:
                events.insert(0, {"listen": "prerequest", "script": {"type": "text/javascript", "exec": s["pre"].split("\n")}})
            item = {"name": s["name"], "event": events, "request": request}
            folder_items.append(walk(item))
        items.append({"name": folder, "item": folder_items})

    return {
        "info": {
            "_postman_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "moviebooking-collection")),
            "name": "Movie Ticket Booking System",
            "description": (
                "End-to-end run of the booking API (same flow as docs/test.md).\n\n"
                "Every URL, ID, login and body is written out in full: no variables.\n\n"
                "**Start from a fresh database and run the requests in order** "
                "(`docker compose down -v && docker compose up -d --build`), then *Run collection* or click through top to "
                "bottom. That makes the IDs exact: screen 7, show 337, seats 721-740, bookings 1 (Riya), 4 (Arjun), 7 (Meera). "
                "Each request checks its expected status and key fields.\n\n"
                "Admin: admin@moviebooking.com / Admin@123 · Customers riya@ / arjun@ / meera@test.com, password Password@123."),
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "item": items,
    }


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "docs", "postman", "MovieBooking.postman_collection.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        json.dump(build(), f, indent=2, ensure_ascii=False)
    print(f"wrote {out} ({sum(len(s) for _, s in FOLDERS)} requests)")
