"""Shared helpers for the interactive demo scripts (Python 3 standard library only, nothing to install).

    python3 scripts/demo/lifecycle.py     one customer's full journey, one curl per Enter
    python3 scripts/demo/stress.py        concurrency, rate limits and failure drills

Both expect the stack to be running: docker compose up -d --build
"""
import base64
import json
import os
import shlex
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

BASE = os.environ.get("BASE_URL", "http://localhost:8080/api")
ADMIN = ("admin@moviebooking.com", "Admin@123")
PASSWORD = "Password@123"
IST = timezone(timedelta(hours=5, minutes=30))
PEEK = "--no-peek" not in sys.argv

# ---------------------------------------------------------------- terminal output

_TTY = sys.stdout.isatty()


def _c(code):
    return (lambda s: f"\033[{code}m{s}\033[0m") if _TTY else (lambda s: s)


bold, dim, red, green, yellow, blue, cyan, magenta = (_c(c) for c in ("1", "2", "31", "32", "33", "34", "36", "35"))


def banner(title, subtitle=""):
    line = "═" * 78
    print(f"\n{cyan(line)}\n{bold(cyan('  ' + title))}")
    if subtitle:
        print(dim("  " + subtitle))
    print(cyan(line))


def step_header(n, total, title, why):
    print(f"\n{bold(magenta(f'━━ Step {n}/{total}: {title} '))}{magenta('━' * max(0, 60 - len(title)))}")
    for line in why.strip().splitlines():
        print(f"  {line.strip()}")


def wait_enter(prompt="Press Enter to run it  (s = skip, q = quit) "):
    try:
        answer = input(yellow(f"\n  ▶ {prompt}")).strip().lower()
    except (EOFError, KeyboardInterrupt):
        print()
        sys.exit(0)
    if answer == "q":
        print(dim("  bye"))
        sys.exit(0)
    return answer != "s"


def ok(msg):
    print(green(f"  ✔ {msg}"))


def bad(msg):
    print(red(f"  ✘ {msg}"))


def check(cond, msg_ok, msg_bad=None):
    ok(msg_ok) if cond else bad(msg_bad or msg_ok)
    return cond


def note(msg):
    print(dim(f"  {msg}"))


# ---------------------------------------------------------------- curl (what the audience sees and what actually runs)

def curl_args(method, path, auth=None, body=None, headers=None):
    args = ["curl", "-s", "-i", "-X", method, BASE + path]
    if auth:
        args += ["--user", f"{auth[0]}:{auth[1]}"]
    for k, v in (headers or {}).items():
        args += ["-H", f"{k}: {v}"]
    if body is not None:
        args += ["-H", "Content-Type: application/json", "--data", json.dumps(body)]
    return args


def show_curl(args):
    """Prints the command in copy-paste form, one option per line."""
    a = [x for x in args if x not in ("-s", "-i")]          # curl -X METHOD URL [option value]...
    lines = [f"curl -X {a[2]} {shlex.quote(a[3])}"]
    rest = a[4:]
    lines += [f"{rest[i]} {shlex.quote(rest[i + 1])}" for i in range(0, len(rest), 2)]
    print(blue("\n  $ " + " \\\n      ".join(lines)))


class Response:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.text = status, headers, body
        try:
            self.json = json.loads(body) if body.strip() else None
        except ValueError:
            self.json = None


def run_curl(method, path, auth=None, body=None, headers=None, show=True, print_body=True, max_lines=40):
    args = curl_args(method, path, auth, body, headers)
    if show:
        show_curl(args)
    out = subprocess.run(args, capture_output=True, text=True).stdout.replace("\r\n", "\n")
    head, _, text = out.partition("\n\n")
    while head.startswith("HTTP/1.1 100"):
        head, _, text = text.partition("\n\n")
    status_line, *header_lines = head.split("\n")
    status = int(status_line.split()[1]) if len(status_line.split()) > 1 else 0
    hdrs = {h.split(":", 1)[0].lower(): h.split(":", 1)[1].strip() for h in header_lines if ":" in h}
    resp = Response(status, hdrs, text)
    if show:
        colour = green if status < 300 else (yellow if status < 400 else red)
        extra = "  ".join(f"{k}: {hdrs[k]}" for k in ("etag", "retry-after", "location") if k in hdrs)
        print(f"\n  {colour(bold(status_line.strip()))}  {dim(extra)}")
        if print_body and text.strip():
            pretty = json.dumps(resp.json, indent=2, ensure_ascii=False) if resp.json is not None else text
            lines = pretty.splitlines()
            for line in lines[:max_lines]:
                print("  " + line)
            if len(lines) > max_lines:
                print(dim(f"  … {len(lines) - max_lines} more lines"))
    return resp


# ---------------------------------------------------------------- plain HTTP for the load scenarios (no output)

def http(method, path, auth=None, body=None, headers=None, timeout=60):
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if auth:
        req.add_header("Authorization", "Basic " + base64.b64encode(f"{auth[0]}:{auth[1]}".encode()).decode())
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw.strip() else None), dict(r.headers)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw) if raw.strip() else None, dict(e.headers)
        except ValueError:
            return e.code, None, dict(e.headers)
    except Exception as e:                     # connection reset, timeout …
        return 0, {"code": type(e).__name__, "message": str(e)}, {}


def fire_together(calls):
    """Runs every call at the same instant (threads wait on a barrier), returns results in order."""
    barrier = threading.Barrier(len(calls))

    def go(call):
        barrier.wait()
        return call()

    with ThreadPoolExecutor(max_workers=len(calls)) as pool:
        return list(pool.map(go, calls))


def register_users(n, prefix):
    run = uuid.uuid4().hex[:6]
    emails = [f"{prefix}{i}.{run}@load.test" for i in range(n)]

    def reg(e):
        return http("POST", "/auth/register", body={"email": e, "password": PASSWORD, "fullName": prefix.title()})[0]

    with ThreadPoolExecutor(max_workers=32) as pool:
        codes = list(pool.map(reg, emails))
    failed = sum(1 for c in codes if c != 201)
    if failed:
        bad(f"{failed} registrations failed")
    return [(e, PASSWORD) for e in emails]


def tally(results):
    counts = {}
    for status, body, _ in results:
        key = f"{status} {body.get('code', '') if isinstance(body, dict) and (status >= 400 or status == 0) else ''}".strip()
        counts[key] = counts.get(key, 0) + 1
    for key, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        colour = green if key.startswith("2") else (yellow if key[:1] in ("4",) else red)
        print(f"    {colour(f'{n:>4} × {key}')}")
    return counts


# ---------------------------------------------------------------- peeking into Postgres, Redis and the app log

def sql(query, title=None):
    if not PEEK:
        return
    if title:
        print(cyan(f"\n  ┌ Postgres: {title}"))
    out = subprocess.run(["docker", "exec", "mtbs-postgres", "psql", "-U", "moviebooking", "-d", "moviebooking",
                          "-P", "pager=off", "-c", query], capture_output=True, text=True)
    for line in (out.stdout or out.stderr).rstrip().splitlines():
        print("  │ " + line)


def sql_value(query):
    out = subprocess.run(["docker", "exec", "mtbs-postgres", "psql", "-U", "moviebooking", "-d", "moviebooking",
                          "-tAc", query], capture_output=True, text=True)
    return out.stdout.strip()


def redis_locks(show_id, title="seat-lock keys (value = booking id or BOOKED, TTL in seconds)"):
    if not PEEK:
        return
    print(cyan(f"\n  ┌ Redis: {title}"))
    r = ["docker", "exec", "mtbs-redis", "redis-cli"]
    keys = subprocess.run(r + ["--scan", "--pattern", f"lock:{{show:{show_id}}}:*"],
                          capture_output=True, text=True).stdout.split()
    if not keys:
        print("  │ (none)")
    for k in sorted(keys):
        val = subprocess.run(r + ["GET", k], capture_output=True, text=True).stdout.strip()
        ttl = subprocess.run(r + ["TTL", k], capture_output=True, text=True).stdout.strip()
        print(f"  │ {k}  →  {bold(val)}   ttl {ttl}s")


def app_log(pattern, since="60s", title="app log"):
    if not PEEK:
        return
    print(cyan(f"\n  ┌ {title}"))
    out = subprocess.run(["docker", "logs", "mtbs-app", "--since", since], capture_output=True, text=True)
    lines = [l for l in (out.stdout + out.stderr).splitlines() if pattern in l]
    for line in lines[-8:] or ["(nothing yet)"]:
        print("  │ " + (line.split(" : ", 1)[1] if " : " in line else line))


def docker(*args):
    return subprocess.run(["docker", *args], capture_output=True, text=True)


# ---------------------------------------------------------------- misc

def seat_grid(seat_map, highlight=()):
    """Draws the seat map: o available · H held · X booked · - blocked; highlighted seats in brackets."""
    rows = {}
    for s in seat_map["seats"]:
        rows.setdefault(s["row"], []).append(s)
    sym = {"AVAILABLE": "o", "HELD": "H", "BOOKED": "X", "BLOCKED": "-"}
    print()
    for row, seats in rows.items():
        cells = []
        for s in sorted(seats, key=lambda x: x["number"]):
            c = sym[s["state"]]
            c = {"o": green, "H": yellow, "X": red, "-": dim}[c](c)
            cells.append(f"[{c}]" if s["seatId"] in highlight else f" {c} ")
        kind = seats[0]["type"].lower()
        print(f"   {row:>2} {''.join(cells)}  {dim(kind + ' ₹' + str(seats[0]['price']))}")
    print(dim("      o available   H held   X booked   - blocked   [ ] your seats"))


def ist(instant):
    whole = instant.split(".")[0].replace("Z", "") + "+00:00"       # drop fractions: Python 3.9 is picky about them
    return datetime.fromisoformat(whole).astimezone(IST).strftime("%a %d %b %H:%M IST")


def require_stack():
    status, _, _ = http("GET", "/cities", timeout=5)
    if status != 200:
        bad(f"The API at {BASE} isn't answering. Start it first:  docker compose up -d --build")
        sys.exit(1)


def pause(seconds, why):
    note(f"waiting {seconds}s: {why}")
    time.sleep(seconds)
