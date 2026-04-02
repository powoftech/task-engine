package processor

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strconv"
	"sync"
	"time"

	"greennode/worker-node/internal/cache"
	"greennode/worker-node/internal/db"

	amqp "github.com/rabbitmq/amqp091-go"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

type JobMessage struct {
	JobID      string `json:"jobId"`
	TaskType   string `json:"taskType"`
	Complexity int    `json:"complexity"`
	TraceID    string `json:"traceId"`
}

type Processor struct {
	dbConn  *db.Database
	cache   *cache.Cache
	rmqConn *amqp.Connection
	channel *amqp.Channel
	tracer  trace.Tracer
}

func New(database *db.Database, redisCache *cache.Cache) (*Processor, error) {
	host := getEnv("MQ_HOST", "localhost")
	port := getEnv("MQ_PORT", "5672")
	user := getEnv("MQ_USER", "green_user")
	pass := getEnv("MQ_PASSWORD", "green_password")

	url := fmt.Sprintf("amqp://%s:%s@%s:%s/", user, pass, host, port)
	conn, err := amqp.Dial(url)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to RabbitMQ: %w", err)
	}

	ch, err := conn.Channel()
	if err != nil {
		return nil, fmt.Errorf("failed to open a channel: %w", err)
	}

	concurrencyStr := getEnv("WORKER_CONCURRENCY", "10")
	concurrency, _ := strconv.Atoi(concurrencyStr)

	err = ch.Qos(
		concurrency,
		0,
		false,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to set QoS: %w", err)
	}

	log.Println("Connected to RabbitMQ successfully")
	return &Processor{
		dbConn:  database,
		cache:   redisCache,
		rmqConn: conn,
		channel: ch,
		tracer:  otel.Tracer("job-processor"),
	}, nil
}

func (p *Processor) Start() error {
	msgs, err := p.channel.Consume(
		"worker.jobs.queue",
		"",
		false,
		false,
		false,
		false,
		nil,
	)
	if err != nil {
		return fmt.Errorf("failed to register a consumer: %w", err)
	}

	log.Println("Worker is waiting for messages...")

	concurrencyStr := getEnv("WORKER_CONCURRENCY", "10")
	concurrency, _ := strconv.Atoi(concurrencyStr)

	var wg sync.WaitGroup
	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for d := range msgs {
				p.processMessage(d)
			}
		}()
	}
	wg.Wait()
	return nil
}

func (p *Processor) processMessage(d amqp.Delivery) {
	var job JobMessage
	if err := json.Unmarshal(d.Body, &job); err != nil {
		log.Printf("Error decoding JSON: %v", err)
		d.Nack(false, false)
		return
	}

	log.Printf("Received Job [%s] Type: %s", job.JobID, job.TaskType)

	ctx := context.Background()
	if job.TraceID != "" && job.TraceID != "no-trace-id" {
		if traceID, err := trace.TraceIDFromHex(job.TraceID); err == nil {
			spanContext := trace.NewSpanContext(trace.SpanContextConfig{
				TraceID:    traceID,
				SpanID:     trace.SpanID{},
				TraceFlags: trace.FlagsSampled,
				Remote:     true,
			})
			ctx = trace.ContextWithSpanContext(ctx, spanContext)
		}
	}

	ctx, span := p.tracer.Start(ctx, "worker.process_job")
	span.SetAttributes(attribute.String("job.id", job.JobID))
	span.SetAttributes(attribute.String("job.task_type", job.TaskType))
	defer span.End()

	cacheCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	acquired, err := p.cache.ClaimJob(cacheCtx, job.JobID)
	if err != nil || !acquired {
		span.AddEvent("job_already_claimed_or_cache_error")
		d.Ack(false)
		return
	}

	log.Printf("Processing Job [%s] with complexity %d...", job.JobID, job.Complexity)
	span.AddEvent("starting_ai_simulation")
	time.Sleep(time.Duration(job.Complexity) * time.Second)
	span.AddEvent("completed_ai_simulation")

	mockResult := fmt.Sprintf(`{"status": "success", "processed_in_seconds": %d}`, job.Complexity)
	if err := p.dbConn.CompleteJob(job.JobID, mockResult); err != nil {
		log.Printf("Failed to update database for job [%s]: %v", job.JobID, err)
		span.RecordError(err)
		d.Nack(false, true)
		return
	}

	log.Printf("Job [%s] completed successfully.", job.JobID)
	d.Ack(false)
}

func (p *Processor) Close() {
	p.channel.Close()
	p.rmqConn.Close()
}

func getEnv(key, fallback string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return fallback
}
