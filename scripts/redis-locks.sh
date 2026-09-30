#!/usr/bin/env bash
# Live view of the seat locks in Redis, refreshed every second (Ctrl-C to stop).
#   ./scripts/redis-locks.sh
# Value = booking id while the seat is held, BOOKED after payment. The TTL counts down; the key disappears on
# cancel or expiry.

while true; do
  clear
  echo "Redis seat locks   $(date +%T)"
  echo "------------------------------------------------------------"
  keys=$(docker exec mtbs-redis redis-cli --scan --pattern 'lock:*' | sort)
  if [ -z "$keys" ]; then
    echo "(no locked seats)"
  fi
  for k in $keys; do
    value=$(docker exec mtbs-redis redis-cli GET "$k")
    ttl=$(docker exec mtbs-redis redis-cli TTL "$k")
    printf "%-32s -> %-8s ttl %ss\n" "$k" "$value" "$ttl"
  done
  sleep 1
done
