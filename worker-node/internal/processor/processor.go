package processor

import (
	"context"
	"encoding/json"
	"errors"
	"expvar"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/google/uuid"
	amqp "github.com/rabbitmq/amqp091-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

const (
	jobRequested  = "job.requested.v1"
	jobProcessing = "job.processing.v1"
	jobCompleted  = "job.completed.v1"
	jobFailed     = "job.failed.v1"

	commandQueue                 = "worker.jobs.queue"
	commandRetryExchange         = "worker.commands.retry"
	commandRetryRoutingKeyPrefix = "worker.jobs.retry."
	commandDeadLetterExchange    = "worker.commands.dlx"
	commandDeadLetterRoutingKey  = "worker.jobs.failed"
	resultExchange               = "worker.results"
	schemaVersion                = 1
	maxAttempts                  = 3
	attemptHeader                = "x-task-engine-attempt"
)

var (
	metricConsumed     = expvar.NewInt("task_engine_worker_events_consumed_total")
	metricCompleted    = expvar.NewInt("task_engine_worker_jobs_completed_total")
	metricFailed       = expvar.NewInt("task_engine_worker_jobs_failed_total")
	metricRejected     = expvar.NewInt("task_engine_worker_events_rejected_total")
	metricPublished    = expvar.NewInt("task_engine_worker_results_published_total")
	metricRetried      = expvar.NewInt("task_engine_worker_events_retried_total")
	metricDeadLettered = expvar.NewInt("task_engine_worker_events_dead_lettered_total")
)

type EventEnvelope struct {
	EventID       string          `json:"eventId"`
	EventType     string          `json:"eventType"`
	SchemaVersion int             `json:"schemaVersion"`
	OccurredAt    time.Time       `json:"occurredAt"`
	Traceparent   string          `json:"traceparent"`
	CorrelationID string          `json:"correlationId"`
	CausationID   string          `json:"causationId"`
	Payload       json.RawMessage `json:"payload"`
}

type JobRequestedPayload struct {
	JobID      string `json:"jobId"`
	TaskType   string `json:"taskType"`
	Complexity int    `json:"complexity"`
}

type JobProcessingPayload struct {
	JobID     string `json:"jobId"`
	StartedAt string `json:"startedAt"`
}

type JobCompletedPayload struct {
	JobID              string         `json:"jobId"`
	ProcessedInSeconds int            `json:"processedInSeconds"`
	Result             map[string]any `json:"result"`
}

type JobFailedPayload struct {
	JobID     string `json:"jobId"`
	ErrorCode string `json:"errorCode"`
	Message   string `json:"message"`
}

type Processor struct {
	rmqConn           *amqp.Connection
	consumeChannel    *amqp.Channel
	publishChannel    *amqp.Channel
	publisherConfirms <-chan amqp.Confirmation
	tracer            trace.Tracer
	publishMu         sync.Mutex
	ready             bool
}

func New() (*Processor, error) {
	host := getEnv("MQ_HOST", "localhost")
	port := getEnv("MQ_PORT", "5672")
	user := getEnv("MQ_USER", "green_user")
	pass := getEnv("MQ_PASSWORD", "")
	scheme := getEnv("MQ_SCHEME", "amqp")

	url := fmt.Sprintf("%s://%s:%s@%s:%s/", scheme, user, pass, host, port)
	conn, err := amqp.Dial(url)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to RabbitMQ: %w", err)
	}

	consumeCh, err := conn.Channel()
	if err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("failed to open consumer channel: %w", err)
	}

	publishCh, err := conn.Channel()
	if err != nil {
		_ = consumeCh.Close()
		_ = conn.Close()
		return nil, fmt.Errorf("failed to open publisher channel: %w", err)
	}

	if err := publishCh.Confirm(false); err != nil {
		_ = publishCh.Close()
		_ = consumeCh.Close()
		_ = conn.Close()
		return nil, fmt.Errorf("failed to enable publisher confirms: %w", err)
	}
	publisherConfirms := publishCh.NotifyPublish(make(chan amqp.Confirmation, 1))

	concurrency := workerConcurrency()
	if err := consumeCh.Qos(concurrency, 0, false); err != nil {
		_ = publishCh.Close()
		_ = consumeCh.Close()
		_ = conn.Close()
		return nil, fmt.Errorf("failed to set QoS: %w", err)
	}

	log.Println("Connected to RabbitMQ successfully")
	return &Processor{
		rmqConn:           conn,
		consumeChannel:    consumeCh,
		publishChannel:    publishCh,
		publisherConfirms: publisherConfirms,
		tracer:            otel.Tracer("job-processor"),
		ready:             true,
	}, nil
}

func (p *Processor) Start(ctx context.Context) error {
	msgs, err := p.consumeChannel.Consume(commandQueue, "", false, false, false, false, nil)
	if err != nil {
		return fmt.Errorf("failed to register consumer: %w", err)
	}

	log.Println("Worker is waiting for job.requested.v1 messages...")

	var wg sync.WaitGroup
	for i := 0; i < workerConcurrency(); i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case d, ok := <-msgs:
					if !ok {
						return
					}
					p.processMessage(d)
				}
			}
		}()
	}
	wg.Wait()
	return nil
}

func (p *Processor) processMessage(d amqp.Delivery) {
	metricConsumed.Add(1)

	envelope, payload, err := decodeJobRequested(d.Body)
	if err != nil {
		metricRejected.Add(1)
		log.Printf("Dead-lettering invalid command: %v", err)
		if err := p.deadLetterCommand(context.Background(), d); err != nil {
			log.Printf("Failed to dead-letter invalid command: %v", err)
			_ = d.Nack(false, true)
			return
		}
		_ = d.Ack(false)
		return
	}

	ctx := otel.GetTextMapPropagator().Extract(
		context.Background(),
		propagation.MapCarrier{"traceparent": envelope.Traceparent},
	)
	ctx, span := p.tracer.Start(ctx, "worker.process_job")
	span.SetAttributes(
		attribute.String("job.id", payload.JobID),
		attribute.String("job.task_type", payload.TaskType),
		attribute.Int("job.complexity", payload.Complexity),
	)
	defer span.End()

	log.Printf("Processing job [%s] type [%s]", payload.JobID, payload.TaskType)
	processing := EventEnvelope{
		EventID:       newUUID(),
		EventType:     jobProcessing,
		SchemaVersion: schemaVersion,
		OccurredAt:    time.Now().UTC(),
		Traceparent:   envelope.Traceparent,
		CorrelationID: envelope.CorrelationID,
		CausationID:   envelope.EventID,
		Payload: mustMarshal(JobProcessingPayload{
			JobID:     payload.JobID,
			StartedAt: time.Now().UTC().Format(time.RFC3339Nano),
		}),
	}
	if err := p.publishWithRetry(ctx, processing); err != nil {
		metricFailed.Add(1)
		span.RecordError(err)
		log.Printf("Failed to publish processing event for job [%s]: %v", payload.JobID, err)
		p.retryOrDeadLetterCommand(ctx, d)
		return
	}

	if payload.TaskType == "force_failure" {
		result := EventEnvelope{
			EventID:       newUUID(),
			EventType:     jobFailed,
			SchemaVersion: schemaVersion,
			OccurredAt:    time.Now().UTC(),
			Traceparent:   envelope.Traceparent,
			CorrelationID: envelope.CorrelationID,
			CausationID:   envelope.EventID,
			Payload: mustMarshal(JobFailedPayload{
				JobID:     payload.JobID,
				ErrorCode: "WORKER_FORCED_FAILURE",
				Message:   "The task type requested a forced worker failure.",
			}),
		}
		if err := p.publishWithRetry(ctx, result); err != nil {
			metricFailed.Add(1)
			span.RecordError(err)
			p.retryOrDeadLetterCommand(ctx, d)
			return
		}
		metricFailed.Add(1)
		log.Printf("Failed job [%s] by request", payload.JobID)
		_ = d.Ack(false)
		return
	}
	time.Sleep(time.Duration(payload.Complexity) * time.Second)

	result := EventEnvelope{
		EventID:       newUUID(),
		EventType:     jobCompleted,
		SchemaVersion: schemaVersion,
		OccurredAt:    time.Now().UTC(),
		Traceparent:   envelope.Traceparent,
		CorrelationID: envelope.CorrelationID,
		CausationID:   envelope.EventID,
		Payload: mustMarshal(JobCompletedPayload{
			JobID:              payload.JobID,
			ProcessedInSeconds: payload.Complexity,
			Result: map[string]any{
				"status":               "success",
				"taskType":             payload.TaskType,
				"processedInSeconds":   payload.Complexity,
				"workerCompletedAtUtc": time.Now().UTC().Format(time.RFC3339Nano),
			},
		}),
	}

	if err := p.publishWithRetry(ctx, result); err != nil {
		metricFailed.Add(1)
		span.RecordError(err)
		log.Printf("Failed to publish result for job [%s]: %v", payload.JobID, err)
		p.retryOrDeadLetterCommand(ctx, d)
		return
	}

	metricCompleted.Add(1)
	log.Printf("Completed job [%s]", payload.JobID)
	_ = d.Ack(false)
}

func (p *Processor) publishWithRetry(ctx context.Context, event EventEnvelope) error {
	var lastErr error
	for attempt := 1; attempt <= 3; attempt++ {
		if err := p.publish(ctx, event); err != nil {
			lastErr = err
			time.Sleep(time.Duration(attempt*attempt) * 200 * time.Millisecond)
			continue
		}
		metricPublished.Add(1)
		return nil
	}
	return lastErr
}

func (p *Processor) publish(ctx context.Context, event EventEnvelope) error {
	body, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to marshal event: %w", err)
	}
	if err := validateEventContract(body); err != nil {
		return fmt.Errorf("result event failed contract validation: %w", err)
	}

	p.publishMu.Lock()
	defer p.publishMu.Unlock()
	err = p.publishChannel.PublishWithContext(
		ctx,
		resultExchange,
		event.EventType,
		false,
		false,
		amqp.Publishing{
			ContentType:   "application/json",
			DeliveryMode:  amqp.Persistent,
			Timestamp:     time.Now().UTC(),
			MessageId:     event.EventID,
			CorrelationId: event.CorrelationID,
			Body:          body,
		},
	)
	if err != nil {
		return fmt.Errorf("failed to publish event: %w", err)
	}

	select {
	case confirmation, ok := <-p.publisherConfirms:
		if !ok {
			return errors.New("publisher confirm channel closed")
		}
		if !confirmation.Ack {
			return errors.New("RabbitMQ negatively acknowledged result event")
		}
		return nil
	case <-time.After(5 * time.Second):
		return errors.New("timed out waiting for publisher confirm")
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (p *Processor) retryOrDeadLetterCommand(ctx context.Context, d amqp.Delivery) {
	nextAttempt := currentAttempt(d.Headers) + 1
	if nextAttempt > maxAttempts {
		if err := p.deadLetterCommand(ctx, d); err != nil {
			log.Printf("Failed to dead-letter command: %v", err)
			_ = d.Nack(false, true)
			return
		}
		metricDeadLettered.Add(1)
		_ = d.Ack(false)
		return
	}

	if err := p.retryCommand(ctx, d, nextAttempt); err != nil {
		log.Printf("Failed to schedule command retry: %v", err)
		_ = d.Nack(false, true)
		return
	}
	metricRetried.Add(1)
	_ = d.Ack(false)
}

func (p *Processor) retryCommand(ctx context.Context, d amqp.Delivery, attempt int) error {
	headers := copyHeaders(d.Headers)
	headers[attemptHeader] = attempt
	return p.publishRawWithConfirm(
		ctx,
		commandRetryExchange,
		fmt.Sprintf("%s%d", commandRetryRoutingKeyPrefix, attempt),
		d.Body,
		headers,
		d.ContentType,
		d.MessageId,
		d.CorrelationId,
	)
}

func (p *Processor) deadLetterCommand(ctx context.Context, d amqp.Delivery) error {
	headers := copyHeaders(d.Headers)
	headers[attemptHeader] = currentAttempt(d.Headers)
	return p.publishRawWithConfirm(
		ctx,
		commandDeadLetterExchange,
		commandDeadLetterRoutingKey,
		d.Body,
		headers,
		d.ContentType,
		d.MessageId,
		d.CorrelationId,
	)
}

func (p *Processor) publishRawWithConfirm(
	ctx context.Context,
	exchange string,
	routingKey string,
	body []byte,
	headers amqp.Table,
	contentType string,
	messageID string,
	correlationID string,
) error {
	if contentType == "" {
		contentType = "application/json"
	}
	p.publishMu.Lock()
	defer p.publishMu.Unlock()
	err := p.publishChannel.PublishWithContext(
		ctx,
		exchange,
		routingKey,
		false,
		false,
		amqp.Publishing{
			ContentType:   contentType,
			DeliveryMode:  amqp.Persistent,
			Timestamp:     time.Now().UTC(),
			MessageId:     messageID,
			CorrelationId: correlationID,
			Headers:       headers,
			Body:          body,
		},
	)
	if err != nil {
		return fmt.Errorf("failed to publish command retry/dead-letter: %w", err)
	}

	select {
	case confirmation, ok := <-p.publisherConfirms:
		if !ok {
			return errors.New("publisher confirm channel closed")
		}
		if !confirmation.Ack {
			return errors.New("RabbitMQ negatively acknowledged command retry/dead-letter")
		}
		return nil
	case <-time.After(5 * time.Second):
		return errors.New("timed out waiting for publisher confirm")
	case <-ctx.Done():
		return ctx.Err()
	}
}

func currentAttempt(headers amqp.Table) int {
	if headers == nil {
		return 0
	}
	switch value := headers[attemptHeader].(type) {
	case int:
		return value
	case int32:
		return int(value)
	case int64:
		return int(value)
	case string:
		parsed, err := strconv.Atoi(value)
		if err != nil {
			return 0
		}
		return parsed
	default:
		return 0
	}
}

func copyHeaders(headers amqp.Table) amqp.Table {
	copy := amqp.Table{}
	for key, value := range headers {
		copy[key] = value
	}
	return copy
}

func decodeJobRequested(body []byte) (EventEnvelope, JobRequestedPayload, error) {
	if err := validateEventContract(body); err != nil {
		return EventEnvelope{}, JobRequestedPayload{}, err
	}
	var envelope EventEnvelope
	if err := json.Unmarshal(body, &envelope); err != nil {
		return envelope, JobRequestedPayload{}, fmt.Errorf("invalid envelope JSON: %w", err)
	}
	if envelope.EventID == "" || envelope.EventType != jobRequested || envelope.SchemaVersion != schemaVersion ||
		envelope.Traceparent == "" || envelope.CorrelationID == "" || envelope.CausationID == "" || len(envelope.Payload) == 0 {
		return envelope, JobRequestedPayload{}, errors.New("envelope failed contract validation")
	}

	var payload JobRequestedPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return envelope, payload, fmt.Errorf("invalid job payload JSON: %w", err)
	}
	if payload.JobID == "" || payload.TaskType == "" || payload.Complexity < 1 || payload.Complexity > 10 {
		return envelope, payload, errors.New("job payload failed contract validation")
	}
	return envelope, payload, nil
}

func (p *Processor) Ready() bool {
	return p.ready && p.rmqConn != nil && !p.rmqConn.IsClosed()
}

func (p *Processor) Close() {
	p.ready = false
	if p.consumeChannel != nil {
		_ = p.consumeChannel.Close()
	}
	if p.publishChannel != nil {
		_ = p.publishChannel.Close()
	}
	if p.rmqConn != nil {
		_ = p.rmqConn.Close()
	}
}

func MetricsHandler(w http.ResponseWriter, r *http.Request) {
	expvar.Handler().ServeHTTP(w, r)
}

func newUUID() string {
	return uuid.NewString()
}

func mustMarshal(value any) json.RawMessage {
	body, err := json.Marshal(value)
	if err != nil {
		panic(err)
	}
	return body
}

func workerConcurrency() int {
	value, err := strconv.Atoi(getEnv("WORKER_CONCURRENCY", "10"))
	if err != nil || value < 1 {
		return 10
	}
	return value
}

func getEnv(key, fallback string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return fallback
}
