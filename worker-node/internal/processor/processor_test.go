package processor

import (
	"encoding/json"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

func TestDecodeJobRequestedRejectsMalformedEnvelope(t *testing.T) {
	if _, _, err := decodeJobRequested([]byte(`{"eventType":"job.requested.v1"}`)); err == nil {
		t.Fatal("expected malformed envelope to fail validation")
	}
}

func TestDecodeJobRequestedAcceptsValidEnvelope(t *testing.T) {
	payload, err := json.Marshal(JobRequestedPayload{
		JobID:      "5a3a6bf4-60e8-414c-9df6-e69e15f2d875",
		TaskType:   "matrix_multiplication",
		Complexity: 1,
	})
	if err != nil {
		t.Fatal(err)
	}

	body, err := json.Marshal(EventEnvelope{
		EventID:       "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
		EventType:     jobRequested,
		SchemaVersion: schemaVersion,
		OccurredAt:    time.Now().UTC(),
		Traceparent:   "00-00000000000000000000000000000000-0000000000000000-00",
		CorrelationID: "e390ea57-8260-469c-8e94-91d17d57f8a1",
		CausationID:   "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
		Payload:       payload,
	})
	if err != nil {
		t.Fatal(err)
	}

	_, decoded, err := decodeJobRequested(body)
	if err != nil {
		t.Fatalf("expected valid command: %v", err)
	}
	if decoded.JobID != "5a3a6bf4-60e8-414c-9df6-e69e15f2d875" {
		t.Fatalf("unexpected job id %q", decoded.JobID)
	}
}

func TestDecodeJobRequestedRejectsInvalidComplexity(t *testing.T) {
	payload, err := json.Marshal(JobRequestedPayload{
		JobID:      "5a3a6bf4-60e8-414c-9df6-e69e15f2d875",
		TaskType:   "matrix_multiplication",
		Complexity: 99,
	})
	if err != nil {
		t.Fatal(err)
	}

	body, err := json.Marshal(EventEnvelope{
		EventID:       "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
		EventType:     jobRequested,
		SchemaVersion: schemaVersion,
		OccurredAt:    time.Now().UTC(),
		Traceparent:   "00-00000000000000000000000000000000-0000000000000000-00",
		CorrelationID: "e390ea57-8260-469c-8e94-91d17d57f8a1",
		CausationID:   "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
		Payload:       payload,
	})
	if err != nil {
		t.Fatal(err)
	}

	if _, _, err := decodeJobRequested(body); err == nil {
		t.Fatal("expected invalid complexity to fail validation")
	}
}

func TestValidateWorkerEventContracts(t *testing.T) {
	for name, event := range map[string]EventEnvelope{
		jobProcessing: {
			EventID:       "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
			EventType:     jobProcessing,
			SchemaVersion: schemaVersion,
			OccurredAt:    time.Now().UTC(),
			Traceparent:   "00-00000000000000000000000000000000-0000000000000000-00",
			CorrelationID: "e390ea57-8260-469c-8e94-91d17d57f8a1",
			CausationID:   "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
			Payload: mustMarshal(JobProcessingPayload{
				JobID:     "5a3a6bf4-60e8-414c-9df6-e69e15f2d875",
				StartedAt: time.Now().UTC().Format(time.RFC3339Nano),
			}),
		},
		jobCompleted: {
			EventID:       "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
			EventType:     jobCompleted,
			SchemaVersion: schemaVersion,
			OccurredAt:    time.Now().UTC(),
			Traceparent:   "00-00000000000000000000000000000000-0000000000000000-00",
			CorrelationID: "e390ea57-8260-469c-8e94-91d17d57f8a1",
			CausationID:   "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
			Payload: mustMarshal(JobCompletedPayload{
				JobID:              "5a3a6bf4-60e8-414c-9df6-e69e15f2d875",
				ProcessedInSeconds: 1,
				Result:             map[string]any{"status": "success"},
			}),
		},
		jobFailed: {
			EventID:       "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
			EventType:     jobFailed,
			SchemaVersion: schemaVersion,
			OccurredAt:    time.Now().UTC(),
			Traceparent:   "00-00000000000000000000000000000000-0000000000000000-00",
			CorrelationID: "e390ea57-8260-469c-8e94-91d17d57f8a1",
			CausationID:   "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
			Payload: mustMarshal(JobFailedPayload{
				JobID:     "5a3a6bf4-60e8-414c-9df6-e69e15f2d875",
				ErrorCode: "WORKER_FAILURE",
				Message:   "failed",
			}),
		},
	} {
		body, err := json.Marshal(event)
		if err != nil {
			t.Fatal(err)
		}
		if err := validateEventContract(body); err != nil {
			t.Fatalf("%s should match contract: %v", name, err)
		}
	}
}

func TestCurrentAttemptReadsRetryHeader(t *testing.T) {
	if got := currentAttempt(amqp.Table{attemptHeader: int32(2)}); got != 2 {
		t.Fatalf("expected attempt 2, got %d", got)
	}
	if got := currentAttempt(amqp.Table{attemptHeader: "3"}); got != 3 {
		t.Fatalf("expected attempt 3, got %d", got)
	}
}
