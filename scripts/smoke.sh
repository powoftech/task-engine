#!/usr/bin/env sh
set -eu

API_URL="${API_URL:-http://localhost:8080}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-90}"
CLIENT_REQUEST_ID="smoke-$(date +%s)"

JOB_ID="$(
  curl -fsS -X POST "$API_URL/api/v1/jobs" \
    -H "Content-Type: application/json" \
    -d "{\"taskType\":\"matrix_multiplication\",\"complexity\":5,\"clientRequestId\":\"$CLIENT_REQUEST_ID\"}" \
  | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
)"

DUPLICATE_STATUS="$(
  curl -sS -o /dev/null -w "%{http_code}" -X POST "$API_URL/api/v1/jobs" \
    -H "Content-Type: application/json" \
    -d "{\"taskType\":\"matrix_multiplication\",\"complexity\":5,\"clientRequestId\":\"$CLIENT_REQUEST_ID\"}"
)"
if [ "$DUPLICATE_STATUS" != "200" ]; then
  echo "Expected duplicate submit to return 200, got $DUPLICATE_STATUS" >&2
  exit 1
fi

LIST_COUNT="$(curl -fsS "$API_URL/api/v1/jobs?clientRequestId=$CLIENT_REQUEST_ID&size=1" | sed -n 's/.*"totalElements"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
if [ -z "$LIST_COUNT" ] || [ "$LIST_COUNT" -lt 1 ]; then
  echo "Expected submitted job to appear in list endpoint" >&2
  exit 1
fi

if [ -z "$JOB_ID" ]; then
  echo "Could not parse job id from API response" >&2
  exit 1
fi

END_TIME=$(( $(date +%s) + TIMEOUT_SECONDS ))
SAW_PROCESSING=0
COMPLETED=0
while [ "$(date +%s)" -lt "$END_TIME" ]; do
  STATUS="$(curl -fsS "$API_URL/api/v1/jobs/$JOB_ID" | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  case "$STATUS" in
    PROCESSING)
      SAW_PROCESSING=1
      ;;
    COMPLETED)
      if [ "$SAW_PROCESSING" -ne 1 ]; then
        echo "Job $JOB_ID completed before smoke test observed PROCESSING" >&2
        exit 1
      fi
      echo "Smoke test passed for job $JOB_ID"
      COMPLETED=1
      break
      ;;
    FAILED|CANCELLED)
      echo "Job $JOB_ID ended with status $STATUS" >&2
      exit 1
      ;;
  esac
  sleep 2
done

if [ "$COMPLETED" -ne 1 ]; then
  echo "Timed out waiting for job $JOB_ID to complete" >&2
  exit 1
fi

CANCEL_ID="smoke-cancel-$(date +%s)"
CANCEL_JOB_ID="$(
  curl -fsS -X POST "$API_URL/api/v1/jobs" \
    -H "Content-Type: application/json" \
    -d "{\"taskType\":\"matrix_multiplication\",\"complexity\":10,\"clientRequestId\":\"$CANCEL_ID\"}" \
  | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
)"
CANCEL_STATUS="$(curl -fsS -X POST "$API_URL/api/v1/jobs/$CANCEL_JOB_ID/cancel" | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
if [ "$CANCEL_STATUS" != "CANCELLED" ]; then
  echo "Expected cancelled job, got $CANCEL_STATUS" >&2
  exit 1
fi

sleep 12
LATE_STATUS="$(curl -fsS "$API_URL/api/v1/jobs/$CANCEL_JOB_ID" | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
if [ "$LATE_STATUS" != "CANCELLED" ]; then
  echo "Late worker result changed cancelled job to $LATE_STATUS" >&2
  exit 1
fi
echo "Cancellation smoke test passed for job $CANCEL_JOB_ID"
