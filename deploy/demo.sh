#!/usr/bin/env bash
# Runs one auction through the REST API: create, bid, a rejected bid, wait for the deadline, read the winner.
set -euo pipefail

API="${API:-http://localhost:8080}"

request() {
  local method="$1" path="$2" body="${3:-}"
  echo "> $method $path $body" >&2
  if [[ -n "$body" ]]; then
    curl -sS -w ' [HTTP %{http_code}]\n' -X "$method" -H 'Content-Type: application/json' -d "$body" "$API$path" | tee /dev/stderr
  else
    curl -sS -w ' [HTTP %{http_code}]\n' "$API$path" | tee /dev/stderr
  fi
}

created=$(request POST /auctions '{"sellerId":1,"startPrice":100,"minIncrement":10,"durationMs":5000}')
id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<<"$created")

request POST "/auctions/$id/bids" '{"bidderId":2,"amount":100}' >/dev/null
request POST "/auctions/$id/bids" '{"bidderId":3,"amount":105}' >/dev/null # too low: expect 409
request POST "/auctions/$id/bids" '{"bidderId":3,"amount":110}' >/dev/null

echo "waiting for the deadline..." >&2
sleep 6

request GET "/auctions/$id" >/dev/null
request POST "/auctions/$id/bids" '{"bidderId":2,"amount":999}' >/dev/null # closed: expect 409
