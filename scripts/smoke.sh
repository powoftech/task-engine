#!/usr/bin/env sh
set -eu

API_URL="${API_URL:-http://localhost:8080}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-90}"
CLIENT_REQUEST_ID="smoke-$(date +%s)"

JOB_ID="$(
  curl -fsS -X POST "$API_URL/api/v1/jobs" \
    -H "Content-Type: application/json" \
    -d "{\"taskType\":\"matrix_multiplication\",\"complexity\":1,\"clientRequestId\":\"$CLIENT_REQUEST_ID\"}" \
  | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
)"

if [ -z "$JOB_ID" ]; then
  echo "Could not parse job id from API response" >&2
  exit 1
fi

END_TIME=$(( $(date +%s) + TIMEOUT_SECONDS ))
while [ "$(date +%s)" -lt "$END_TIME" ]; do
  STATUS="$(curl -fsS "$API_URL/api/v1/jobs/$JOB_ID" | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  case "$STATUS" in
    COMPLETED)
      echo "Smoke test passed for job $JOB_ID"
      exit 0
      ;;
    FAILED|CANCELLED)
      echo "Job $JOB_ID ended with status $STATUS" >&2
      exit 1
      ;;
  esac
  sleep 2
done

echo "Timed out waiting for job $JOB_ID to complete" >&2
exit 1
