# Task Engine _(task-engine)_

A learning POC for reliable distributed task execution with Spring Boot, Go, PostgreSQL, Debezium, and RabbitMQ.

Task Engine is a proof-of-concept system for studying how distributed services can coordinate asynchronous work without losing state or duplicating side effects. It accepts task requests over HTTP, stores job state in PostgreSQL, emits durable outbox events through Debezium, processes jobs in a Go worker through RabbitMQ, and applies worker result events back to the API service.

This repository is intentionally small enough to read end to end, but it includes patterns that matter in production systems: transactional outbox, event contracts, idempotency, retries, dead-letter queues, health checks, metrics, and OpenTelemetry tracing. It is built for local learning and experimentation, not as a production-ready task platform.

## Table of Contents

- [Security](#security)
- [Background](#background)
- [Install](#install)
- [Usage](#usage)
- [Architecture](#architecture)
- [Configuration](#configuration)
- [Event Contracts](#event-contracts)
- [Observability](#observability)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [API](#api)
- [Contributing](#contributing)
- [License](#license)

## Security

This project is a local POC. Do not deploy it to a public network without a security pass.

The sample configuration uses local service credentials, exposes the API on port `8080`, exposes RabbitMQ Management on port `15672`, and does not include authentication or authorization for the job API. Treat `.env` as local-only configuration, rotate any credentials before sharing an environment, and keep real secrets out of commits.

## Background

The POC focuses on one common distributed-systems problem: accepting work synchronously while doing the actual processing asynchronously and reliably.

The system demonstrates these ideas:

- Transactional outbox: the API persists a job and its `job.requested.v1` event in the same PostgreSQL transaction.
- Change data capture: Debezium reads the `outbox_events` table and publishes command events to RabbitMQ.
- Worker isolation: the Go worker consumes job commands, simulates work, and publishes result events.
- Idempotency: callers can send `clientRequestId` to avoid creating duplicate jobs.
- Event contracts: both Java and Go validate JSON event envelopes against shared schemas in [contracts/events](contracts/events).
- Retry and dead-letter behavior: command and result queues include retry queues and DLQs for failed processing.
- Observability: services expose health checks, metrics, and traces for local inspection.

The implementation is intentionally polyglot:

- `api-gateway` is a Spring Boot service with REST endpoints, PostgreSQL persistence, Flyway migrations, RabbitMQ result consumption, and Micrometer/OpenTelemetry instrumentation.
- `worker-node` is a Go service that consumes RabbitMQ commands, validates contracts, emits processing/completed/failed events, and exposes health and metrics endpoints.
- `infra` contains local infrastructure configuration for PostgreSQL, RabbitMQ, Debezium, Jaeger, and load testing.

## Install

The simplest way to run the POC is Docker Compose.

Dependencies:

- Docker with Docker Compose support
- `curl` for shell examples
- PowerShell 7+ if you use the Windows smoke script
- Java 25 and Maven wrapper support for running API tests locally
- Go 1.26.1 for running worker tests locally
- k6 if you want to run the optional load test

Create local environment configuration:

```sh
cp .env.example .env
```

On Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Edit `.env` if you want different local passwords or worker concurrency. Then build and start the full stack:

```sh
docker compose up --build -d
```

Confirm the containers are healthy:

```sh
docker compose ps
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8090/healthz
```

Stop the stack:

```sh
docker compose down
```

Remove persistent local volumes when you want a clean database, RabbitMQ state, and Debezium offsets:

```sh
docker compose down -v
```

## Usage

Submit a job:

```sh
curl -i -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{"taskType":"matrix_multiplication","complexity":3,"clientRequestId":"demo-001"}'
```

New jobs return `202 Accepted`. Reusing the same `clientRequestId` returns `200 OK` with the original job instead of creating a duplicate:

```sh
curl -i -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{"taskType":"matrix_multiplication","complexity":3,"clientRequestId":"demo-001"}'
```

Get a job by id:

```sh
curl http://localhost:8080/api/v1/jobs/<job-id>
```

List jobs:

```sh
curl "http://localhost:8080/api/v1/jobs?size=20"
curl "http://localhost:8080/api/v1/jobs?status=COMPLETED"
curl "http://localhost:8080/api/v1/jobs?clientRequestId=demo-001&size=1"
```

Cancel a queued or processing job:

```sh
curl -X POST http://localhost:8080/api/v1/jobs/<job-id>/cancel
```

Force a worker failure path:

```sh
curl -i -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{"taskType":"force_failure","complexity":1,"clientRequestId":"fail-demo-001"}'
```

Run the end-to-end smoke test after the stack is healthy:

```sh
./scripts/smoke.sh
```

On Windows PowerShell:

```powershell
.\scripts\smoke.ps1
```

The smoke test submits a job, verifies idempotent submission, waits for the `PROCESSING` and `COMPLETED` states, cancels another job, and checks that a late worker result does not overwrite `CANCELLED`.

## Architecture

Runtime flow:

1. A client submits `POST /api/v1/jobs`.
2. The API validates the request and stores a `QUEUED` job in PostgreSQL.
3. In the same transaction, the API stores a `job.requested.v1` event in `outbox_events`.
4. Debezium tails PostgreSQL changes and publishes the outbox event to the RabbitMQ `cdc.events` exchange.
5. RabbitMQ routes `job.requested.v1` to `worker.jobs.queue`.
6. The Go worker validates the event contract, publishes `job.processing.v1`, simulates work, and publishes either `job.completed.v1` or `job.failed.v1`.
7. RabbitMQ routes worker result events to `api.job-results.queue`.
8. The API result listener validates each result event, records processed event ids, and updates the job state idempotently.

Main local services:

- `postgres`: stores job state, outbox events, and processed result event ids.
- `rabbitmq`: routes commands, results, retries, and dead letters.
- `debezium`: turns outbox table changes into RabbitMQ messages.
- `api-gateway`: exposes the HTTP API and applies worker results.
- `worker-node`: consumes job commands and publishes worker events.
- `jaeger`: receives and displays local traces.

Important RabbitMQ routes:

- `cdc.events` exchange with `job.requested.v1` routing key to `worker.jobs.queue`.
- `worker.results` exchange with `job.processing.v1`, `job.completed.v1`, and `job.failed.v1` routing keys to `api.job-results.queue`.
- `worker.commands.retry` and `worker.results.retry` for delayed retry queues.
- `worker.commands.dlx` and `worker.results.dlx` for dead-letter queues.

## Configuration

The stack reads local defaults from `compose.yaml` and optional overrides from `.env`.

Common environment variables:

| Variable                       | Used by                         | Default                              | Purpose                                                 |
| ------------------------------ | ------------------------------- | ------------------------------------ | ------------------------------------------------------- |
| `POSTGRES_USER`                | PostgreSQL, API, Debezium       | `green_user`                         | PostgreSQL username                                     |
| `POSTGRES_PASSWORD`            | PostgreSQL, API, Debezium       | `green_password` in Compose fallback | PostgreSQL password                                     |
| `POSTGRES_DB`                  | PostgreSQL, API, Debezium       | `task_engine`                        | PostgreSQL database                                     |
| `RABBITMQ_USER`                | RabbitMQ, API, worker, Debezium | `green_user`                         | RabbitMQ username                                       |
| `RABBITMQ_PASSWORD`            | RabbitMQ, API, worker, Debezium | `green_password` in Compose fallback | RabbitMQ password                                       |
| `WORKER_CONCURRENCY`           | worker                          | `10`                                 | Number of worker goroutines and RabbitMQ prefetch count |
| `TRACING_SAMPLING_PROBABILITY` | API                             | `1.0`                                | Spring tracing sample probability                       |

Useful local ports:

| Port    | Service                          | URL                      |
| ------- | -------------------------------- | ------------------------ |
| `8080`  | API Gateway                      | `http://localhost:8080`  |
| `8090`  | Worker health and expvar metrics | `http://localhost:8090`  |
| `15672` | RabbitMQ Management              | `http://localhost:15672` |
| `16686` | Jaeger UI                        | `http://localhost:16686` |

## Event Contracts

The event catalog is documented in [contracts/asyncapi.yaml](contracts/asyncapi.yaml). Concrete JSON schemas live in [contracts/events](contracts/events).

All events use a shared envelope:

```json
{
  "eventId": "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
  "eventType": "job.requested.v1",
  "schemaVersion": 1,
  "occurredAt": "2026-05-26T00:00:00Z",
  "traceparent": "00-00000000000000000000000000000000-0000000000000000-00",
  "correlationId": "e390ea57-8260-469c-8e94-91d17d57f8a1",
  "causationId": "53fe2c29-98e1-407f-9a2e-8d6622e5f4db",
  "payload": {}
}
```

Current event types:

- `job.requested.v1`: API to worker command created from the outbox.
- `job.processing.v1`: worker to API event indicating work has started.
- `job.completed.v1`: worker to API event with result payload.
- `job.failed.v1`: worker to API event with error code and message.

Both services validate the envelope and payload before applying behavior. Unknown event types, missing required fields, invalid UUIDs, invalid timestamps, invalid complexity values, and unexpected fields are rejected.

## Observability

Health endpoints:

```sh
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8090/healthz
```

Metrics endpoints:

```sh
curl http://localhost:8080/actuator/prometheus
curl http://localhost:8090/metrics
```

Trace UI:

```text
http://localhost:16686
```

RabbitMQ Management UI:

```text
http://localhost:15672
```

Use the RabbitMQ credentials from `.env`, or the Compose fallback credentials if you did not create `.env`.

## Testing

Run API tests:

```sh
cd api-gateway
./mvnw test
```

On Windows:

```powershell
cd api-gateway
.\mvnw.cmd test
```

Run worker tests:

```sh
cd worker-node
go test ./...
```

Run the full local smoke test:

```sh
docker compose up --build -d
./scripts/smoke.sh
```

Run the optional k6 load test:

```sh
k6 run infra/load-tests/k6-script.js
```

With a non-default API URL:

```sh
API_URL=http://localhost:8080 k6 run infra/load-tests/k6-script.js
```

Format Java code:

```sh
cd api-gateway
./mvnw spotless:apply
```

Format Go code:

```sh
cd worker-node
go fmt ./...
```

## Troubleshooting

If the API is not ready, inspect service logs:

```sh
docker compose logs api-gateway
docker compose logs postgres
docker compose logs rabbitmq
docker compose logs debezium
```

If jobs stay `QUEUED`, check Debezium and RabbitMQ routing:

```sh
docker compose logs debezium
docker compose logs rabbitmq
docker compose logs worker-node
```

If jobs reach `PROCESSING` but never finish, inspect the worker logs and `worker.jobs.dlq` in the RabbitMQ Management UI.

If worker result events are not applied, inspect the API logs and `api.job-results.dlq` in the RabbitMQ Management UI.

If contract validation fails in Docker but passes locally, confirm the `contracts` directory is copied into the relevant image and mounted in the expected working directory.

If a previous run left stale database, RabbitMQ, or Debezium state, reset local volumes:

```sh
docker compose down -v
docker compose up --build -d
```

## API

Base URL:

```text
http://localhost:8080
```

### `POST /api/v1/jobs`

Creates a job and publishes a durable outbox event.

Request body:

```json
{
  "taskType": "matrix_multiplication",
  "complexity": 3,
  "clientRequestId": "demo-001"
}
```

Fields:

- `taskType`: required non-blank string. Use `force_failure` to exercise the worker failure path.
- `complexity`: required integer from `1` through `10`. The worker uses this as simulated processing seconds.
- `clientRequestId`: optional string up to 128 characters for idempotent submission.

Responses:

- `202 Accepted`: new job created.
- `200 OK`: duplicate `clientRequestId`; existing job returned.
- `400 Bad Request`: request validation failed.

### `GET /api/v1/jobs`

Lists jobs using Spring pageable response format.

Query parameters:

- `status`: optional `PENDING`, `QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`, or `CANCELLED`.
- `clientRequestId`: optional idempotency key filter.
- `page`: optional zero-based page number.
- `size`: optional page size.
- `sort`: optional Spring sort expression.

Example:

```sh
curl "http://localhost:8080/api/v1/jobs?status=COMPLETED&page=0&size=10"
```

### `GET /api/v1/jobs/{jobId}`

Returns one job by UUID.

Responses:

- `200 OK`: job found.
- `404 Not Found`: job id does not exist.

### `POST /api/v1/jobs/{jobId}/cancel`

Attempts to cancel a job.

Responses:

- `200 OK`: job found; response body contains the current state after the cancel attempt.
- `404 Not Found`: job id does not exist.

Cancellation is state-aware. A late worker result should not overwrite a terminal `CANCELLED`, `COMPLETED`, or `FAILED` job.

### Job Response Shape

```json
{
  "id": "5a3a6bf4-60e8-414c-9df6-e69e15f2d875",
  "taskType": "matrix_multiplication",
  "complexity": 3,
  "status": "COMPLETED",
  "result": "{\"status\":\"success\"}",
  "failureMessage": null,
  "clientRequestId": "demo-001",
  "correlationId": "e390ea57-8260-469c-8e94-91d17d57f8a1",
  "createdAt": "2026-05-26T00:00:00Z",
  "updatedAt": "2026-05-26T00:00:03Z"
}
```

## Contributing

This is a learning POC, so contributions should keep the code easy to inspect and reason about.

Before opening a pull request:

- Keep changes scoped to one learning goal or behavior.
- Add or update tests for service logic, event contracts, retries, or state transitions.
- Run the relevant Java, Go, and smoke tests.
- Update this README when changing runtime behavior, ports, event contracts, or local setup.
- Avoid committing local `.env` files, generated volumes, or credentials.

Questions can be asked through the repository issue tracker or directly in the pull request discussion. Pull requests are accepted when they preserve the POC's learning focus and include enough verification for the behavior changed.

## License

Apache-2.0 © 2026 Phuong Dang. See [LICENSE](LICENSE).
