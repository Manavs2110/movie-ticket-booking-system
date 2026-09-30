#!/usr/bin/env bash
# Live view of seat locks in BOTH layers, refreshed every second. Leave it running while you book.
#   ./scripts/watch-locks.sh            follows the show with the most recent lock activity
#   ./scripts/watch-locks.sh 337        follows show 337
# Ctrl-C to stop.
set -uo pipefail

SHOW="${1:-}"
PSQL=(docker exec mtbs-postgres psql -U moviebooking -d moviebooking -P pager=off -q)
REDIS=(docker exec mtbs-redis redis-cli)
B=$'\033[1m'; D=$'\033[2m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; C=$'\033[36m'; N=$'\033[0m'

while true; do
  show="$SHOW"
  if [[ -z "$show" ]]; then
    show=$("${PSQL[@]}" -tAc "SELECT show_id FROM booking ORDER BY updated_at DESC, id DESC LIMIT 1")
  fi
  out="${B}Live seat locks${N}  ${D}$(date '+%H:%M:%S')  ·  show ${show:-none}  ·  Ctrl-C to stop${N}\n\n"

  out+="${C}${B}Layer 2 · Postgres seat_lock${N} ${D}(the owner: a row exists only while a seat is locked or sold)${N}\n"
  out+="$("${PSQL[@]}" -c "
    SELECT s.row_label || s.seat_number AS seat, sl.booking_id AS booking,
           CASE WHEN sl.booked THEN 'BOOKED' WHEN sl.held_until > now() THEN 'LOCKED' ELSE 'expired' END AS state,
           CASE WHEN sl.booked THEN '-' ELSE greatest(0, extract(epoch FROM sl.held_until - now())::int)::text || ' s' END AS time_left,
           to_char(sl.held_until AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') AS held_until_ist
    FROM seat_lock sl JOIN seat s ON s.id = sl.seat_id
    WHERE sl.show_id = ${show:-0} ORDER BY s.id;" | sed -e "s/BOOKED/${R}BOOKED${N}/; s/LOCKED/${Y}LOCKED${N}/; s/expired/${D}expired${N}/")\n\n"

  out+="${C}${B}Layer 1 · Redis filter${N} ${D}(key lock:{show:${show}}:seat:<id> → booking id or BOOKED; TTL counts down)${N}\n"
  keys=$("${REDIS[@]}" --scan --pattern "lock:{show:${show:-0}}:seat:*" 2>/dev/null | sort)
  if [[ -z "$keys" ]]; then
    out+="  ${D}(no keys)${N}\n"
  else
    while read -r k; do
      [[ -z "$k" ]] && continue
      val=$("${REDIS[@]}" GET "$k"); ttl=$("${REDIS[@]}" TTL "$k")
      [[ "$val" == "BOOKED" ]] && val="${R}BOOKED${N}" || val="${Y}${val}${N}"
      out+="  ${k}  →  ${val}   ${D}TTL ${ttl} s${N}\n"
    done <<< "$keys"
  fi
  sm=$("${REDIS[@]}" PTTL "seatmap:${show:-0}" 2>/dev/null)
  out+="  ${D}seat-map micro-cache seatmap:${show}: $([[ "$sm" -gt 0 ]] 2>/dev/null && echo "cached, ${sm} ms left" || echo "empty")${N}\n\n"

  out+="${C}${B}Bookings on this show${N} ${D}(status as stored; HELD past its time is reported EXPIRED by the API)${N}\n"
  out+="$("${PSQL[@]}" -c "
    SELECT b.id, u.email, b.status, b.total_amount AS total,
           to_char(b.hold_expires_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') AS lock_until_ist
    FROM booking b JOIN app_user u ON u.id = b.user_id WHERE b.show_id = ${show:-0}
    ORDER BY b.id DESC LIMIT 6;" | sed -e "s/CONFIRMED/${G}CONFIRMED${N}/; s/ HELD/ ${Y}HELD${N}/")\n\n"

  out+="${C}${B}Events (outbox → Kafka)${N}\n"
  out+="$("${PSQL[@]}" -c "
    SELECT o.id, o.event_type, o.aggregate_id AS booking, o.status,
           to_char(o.next_attempt_at AT TIME ZONE 'Asia/Kolkata', 'DD Mon HH24:MI') AS due_ist
    FROM outbox_event o JOIN booking b ON b.id = o.aggregate_id
    WHERE b.show_id = ${show:-0} ORDER BY o.id DESC LIMIT 6;" | sed -e "s/ PROCESSED/ ${G}PROCESSED${N}/; s/ IN_QUEUE/ ${C}IN_QUEUE${N}/; s/ NOT_STARTED/ ${Y}NOT_STARTED${N}/")\n"

  printf '\033[H\033[2J%b' "$out"
  sleep 1
done
