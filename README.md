# Distributed Task Execution Engine

A production-oriented microservices learning project for asynchronous job execution, event-driven messaging, CDC outbox delivery, observability, and AWS-compatible container deployment patterns.

This repository is intentionally structured as a local-first distributed systems lab. The default path is to run the whole system with Docker Compose, inspect the service boundaries, submit jobs through the API, watch events move through RabbitMQ and Debezium, and then evolve the design toward an ECS Fargate deployment.

## What This Project Demonstrates

- A Java/Spring API service that owns job state and exposes HTTP endpoints.
- A Go worker service that performs asynchronous work without direct database access.
- PostgreSQL as the API-owned system of record.
- Flyway migrations owned by the API service.
- Transactional outbox events written with job state changes.
- Debezium Server reading the outbox table through PostgreSQL logical replication.
- RabbitMQ exchanges, durable queues, dead-letter queues, manual acknowledgements, and publisher confirms.
- Idempotent result processing through a `processed_events` table.
- OpenTelemetry tracing to Jaeger.
- Metrics and health endpoints for both application services.
- A Terraform scaffold for a future AWS ECS/RDS/Amazon MQ deployment path.

## Architecture

```text
                           HTTP
                     POST /api/v1/jobs
                     GET /api/v1/jobs/{id}
                             |
                             v
                  +----------------------+
                  |  API Gateway         |
                  |  Java / Spring Boot  |
                  +----------+-----------+
                             |
             transaction: jobs + outbox_events
                             |
                             v
                  +----------------------+
                  |  PostgreSQL          |
                  |  API-owned schema    |
                  +----------+-----------+
                             |
             logical replication / CDC
                             |
                             v
                  +----------------------+
                  |  Debezium Server     |
                  |  Outbox relay        |
                  +----------+-----------+
                             |
                     job.requested.v1
                             |
                             v
                  +----------------------+
                  |  RabbitMQ            |
                  |  cdc.events exchange |
                  +----------+-----------+
                             |
                             v
                  +----------------------+
                  |  Worker Node         |
                  |  Go                  |
                  +----------+-----------+
                             |
              job.completed.v1 / job.failed.v1
                             |
                             v
                  +----------------------+
                  |  RabbitMQ            |
                  |  worker.results      |
                  +----------+-----------+
                             |
                             v
                  +----------------------+
                  |  API result consumer |
                  |  Updates job state   |
                  +----------------------+
```

### Service Ownership

The API service owns the PostgreSQL schema and all job state transitions. Workers do not write to the database. Workers consume command events from RabbitMQ, perform work, and publish result events. The API consumes those result events and updates job state idempotently.

This is the central boundary in the project:

- API owns `jobs`, `outbox_events`, and `processed_events`.
- Debezium reads only `outbox_events`.
- Worker owns no persistent database state.
- RabbitMQ is the integration boundary between services.

## Repository Layout

```text
.
|-- api-gateway/                 Java/Spring Boot API service
|   |-- src/main/java/            Controllers, service layer, entities, messaging
|   |-- src/main/resources/       Spring config and Flyway migrations
|   `-- pom.xml
|-- worker-node/                 Go worker service
|   |-- cmd/worker/               Worker entry point
|   |-- internal/processor/       RabbitMQ consumer and result publisher
|   |-- internal/telemetry/       OpenTelemetry setup
|   `-- go.mod
|-- contracts/                   AsyncAPI and JSON Schema event contracts
|   |-- asyncapi.yaml
|   `-- events/
|-- infra/
|   |-- debezium/                 Debezium Server config
|   |-- rabbitmq/                 RabbitMQ definitions
|   |-- jaeger/                   Jaeger image wrapper
|   |-- load-tests/               k6 script
|   `-- terraform/aws/           AWS infrastructure scaffold
|-- scripts/                     Local smoke scripts
|-- compose.yaml                 Local runtime stack
`-- .env.example                 Local environment defaults
```

## Prerequisites

For local execution:

- Docker Desktop or Docker Engine with Compose support.
- PowerShell, Bash, or another shell capable of running the provided scripts.
- `curl` if you run the shell smoke script manually.

For service-level development outside containers:

- Java 25 and Maven for `api-gateway`.
- Go 1.26 for `worker-node`.
- PostgreSQL and RabbitMQ if not using Compose.

The easiest path is Docker Compose; it builds and runs all required services.

## Quick Start

1. Copy the example environment file if you want to override defaults:

   ```powershell
   Copy-Item .env.example .env
   ```

   On macOS/Linux:

   ```sh
   cp .env.example .env
   ```

2. Start the full stack:

   ```sh
   docker compose up --build
   ```

   If your environment uses the legacy Compose binary:

   ```sh
   docker-compose up --build
   ```

3. Wait until services are healthy:

   ```sh
   docker compose ps
   ```

4. Run the smoke test:

   PowerShell:

   ```powershell
   .\scripts\smoke.ps1
   ```

   Bash:

   ```sh
   ./scripts/smoke.sh
   ```

   Expected output:

   ```text
   Smoke test passed for job <job-id>
   ```

## Local Services

| Service                | URL / Port                                  | Purpose                           |
| ---------------------- | ------------------------------------------- | --------------------------------- |
| API Gateway            | `http://localhost:8080`                     | Job API and result event consumer |
| API Actuator health    | `http://localhost:8080/actuator/health`     | Spring health endpoint            |
| API Prometheus metrics | `http://localhost:8080/actuator/prometheus` | API metrics                       |
| Worker health          | `http://localhost:8090/healthz`             | Worker readiness                  |
| Worker metrics         | `http://localhost:8090/metrics`             | Worker expvar metrics             |
| RabbitMQ AMQP          | `localhost:5672`                            | Message broker                    |
| RabbitMQ UI            | `http://localhost:15672`                    | Broker management UI              |
| Jaeger UI              | `http://localhost:16686`                    | Trace exploration                 |
| OTLP gRPC              | `localhost:4317`                            | OpenTelemetry collector endpoint  |
| OTLP HTTP              | `localhost:4318`                            | OpenTelemetry collector endpoint  |

Default RabbitMQ credentials:

```text
username: green_user
password: green_password
```

These credentials are local development defaults only. Do not reuse them for deployed environments.

## API Usage

### Submit a Job

```sh
curl -i -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "taskType": "matrix_multiplication",
    "complexity": 1,
    "clientRequestId": "demo-001"
  }'
```

The API returns `202 Accepted`.

Example response:

```json
{
  "id": "339ac531-edd8-45a6-84b4-e69ab72e864e",
  "taskType": "matrix_multiplication",
  "complexity": 1,
  "status": "QUEUED",
  "result": null,
  "failureMessage": null,
  "clientRequestId": "demo-001",
  "correlationId": "a6f4f9ae-07c8-43c7-b85c-1c77a796fbbb",
  "createdAt": "2026-05-25T08:45:22.421Z",
  "updatedAt": "2026-05-25T08:45:22.421Z"
}
```

Request fields:

| Field             | Required | Description                                                                                   |
| ----------------- | -------- | --------------------------------------------------------------------------------------------- |
| `taskType`        | Yes      | Name of the work to run. Use `force_failure` to exercise the failed-result path.              |
| `complexity`      | Yes      | Integer from `1` to `10`. The worker currently sleeps for this many seconds to simulate work. |
| `clientRequestId` | No       | Optional idempotency key, max 128 characters. Reusing it returns the existing job.            |

### Fetch Job Status

```sh
curl http://localhost:8080/api/v1/jobs/<job-id>
```

Possible statuses:

- `PENDING`
- `QUEUED`
- `PROCESSING`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

The current flow sets new jobs to `QUEUED`, then result events move them to `COMPLETED` or `FAILED`.

### Exercise the Failure Path

```sh
curl -i -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "taskType": "force_failure",
    "complexity": 1,
    "clientRequestId": "demo-failure-001"
  }'
```

The worker publishes `job.failed.v1`, and the API records the job as `FAILED`.

## Event Contracts

Event contracts live under [contracts](contracts).

- [contracts/asyncapi.yaml](contracts/asyncapi.yaml) defines the asynchronous channels.
- [contracts/events/job-requested.v1.schema.json](contracts/events/job-requested.v1.schema.json) defines the command event.
- [contracts/events/job-completed.v1.schema.json](contracts/events/job-completed.v1.schema.json) defines successful result events.
- [contracts/events/job-failed.v1.schema.json](contracts/events/job-failed.v1.schema.json) defines failed result events.

All messages use the same envelope:

```json
{
  "eventId": "uuid",
  "eventType": "job.requested.v1",
  "schemaVersion": 1,
  "occurredAt": "2026-05-25T08:45:22.421Z",
  "traceparent": "00-...",
  "correlationId": "uuid",
  "causationId": "uuid",
  "payload": {}
}
```

Event types:

| Event              | Publisher                 | Consumer | Purpose                  |
| ------------------ | ------------------------- | -------- | ------------------------ |
| `job.requested.v1` | Debezium, from API outbox | Worker   | Command to process a job |
| `job.completed.v1` | Worker                    | API      | Successful job result    |
| `job.failed.v1`    | Worker                    | API      | Failed job result        |

## Persistence Model

The API owns database migrations through Flyway.

Current migration:

- [api-gateway/src/main/resources/db/migration/V1\_\_initial_schema.sql](api-gateway/src/main/resources/db/migration/V1__initial_schema.sql)

Tables:

| Table                   | Purpose                                                  |
| ----------------------- | -------------------------------------------------------- |
| `jobs`                  | Source of truth for job state and result data            |
| `outbox_events`         | Transactional outbox rows emitted by the API             |
| `processed_events`      | Idempotency record for result events consumed by the API |
| `flyway_schema_history` | Flyway migration metadata                                |

The job creation transaction writes both:

1. A `jobs` row.
2. A `job.requested.v1` envelope into `outbox_events`.

Debezium relays outbox rows to RabbitMQ. The worker never reads or writes PostgreSQL directly.

## Messaging Topology

RabbitMQ definitions are in [infra/rabbitmq/definitions.json](infra/rabbitmq/definitions.json).

Exchanges:

| Exchange              | Type   | Purpose                                               |
| --------------------- | ------ | ----------------------------------------------------- |
| `cdc.events`          | topic  | Debezium publishes outbox command events              |
| `worker.results`      | direct | Worker publishes job result events                    |
| `worker.commands.dlx` | direct | Dead-letter exchange for worker command failures      |
| `worker.results.dlx`  | direct | Dead-letter exchange for API result-consumer failures |

Queues:

| Queue                   | Bound From            | Routing Key                         |
| ----------------------- | --------------------- | ----------------------------------- |
| `worker.jobs.queue`     | `cdc.events`          | `job.requested.v1`                  |
| `worker.jobs.dlq`       | `worker.commands.dlx` | `worker.jobs.failed`                |
| `api.job-results.queue` | `worker.results`      | `job.completed.v1`, `job.failed.v1` |
| `api.job-results.dlq`   | `worker.results.dlx`  | `api.job-results.failed`            |

Reliability behavior:

- Worker consumes commands with manual acknowledgement.
- Worker publishes results with publisher confirms.
- Invalid command messages are rejected and dead-lettered.
- API consumes result events with manual acknowledgement.
- Invalid result messages are rejected and dead-lettered.
- Duplicate result events are ignored through `processed_events.event_id`.

## Debezium Outbox Relay

Debezium Server config is in [infra/debezium/application.properties](infra/debezium/application.properties).

Local behavior:

- Connects to PostgreSQL using logical replication.
- Includes only `public.outbox_events`.
- Uses the Debezium outbox event router.
- Publishes command events to RabbitMQ exchange `cdc.events`.
- Routes local outbox events as `job.requested.v1`.
- Stores offsets in the `debezium_data` Compose volume.

PostgreSQL uses [infra/db-init/postgresql.conf](infra/db-init/postgresql.conf) to enable logical replication for the local database.

## Observability

### Tracing

Jaeger is included in the Compose stack.

Open the UI:

```text
http://localhost:16686
```

The API exports traces through OTLP HTTP to `http://jaeger:4318/v1/traces`.

The worker exports traces through OTLP gRPC to `http://jaeger:4317`.

### API Metrics

```sh
curl http://localhost:8080/actuator/prometheus
```

Notable custom API metrics:

- `task_engine_jobs_submitted_total`
- `task_engine_jobs_completed_total`
- `task_engine_jobs_failed_total`
- `task_engine_events_duplicates_total`
- `task_engine_events_results_consumed_total`
- `task_engine_events_results_rejected_total`

Spring Actuator also exposes default JVM, HTTP, and application metrics.

### Worker Metrics

```sh
curl http://localhost:8090/metrics
```

Notable worker metrics:

- `task_engine_worker_events_consumed_total`
- `task_engine_worker_jobs_completed_total`
- `task_engine_worker_jobs_failed_total`
- `task_engine_worker_events_rejected_total`
- `task_engine_worker_results_published_total`

## Configuration

Local defaults are defined in [.env.example](.env.example).

| Variable             | Default          | Used By                             |
| -------------------- | ---------------- | ----------------------------------- |
| `POSTGRES_USER`      | `green_user`     | PostgreSQL, API, Debezium           |
| `POSTGRES_PASSWORD`  | `green_password` | PostgreSQL, API, Debezium           |
| `POSTGRES_DB`        | `task_engine`    | PostgreSQL, API, Debezium           |
| `RABBITMQ_USER`      | `green_user`     | RabbitMQ, API, Worker, Debezium     |
| `RABBITMQ_PASSWORD`  | `green_password` | RabbitMQ, API, Worker, Debezium     |
| `WORKER_CONCURRENCY` | `10`             | Worker prefetch and goroutine count |

API-specific environment variables:

| Variable                           | Default                           |
| ---------------------------------- | --------------------------------- |
| `DB_HOST`                          | `localhost`                       |
| `DB_PORT`                          | `5432` in Compose                 |
| `DB_USER`                          | `green_user`                      |
| `DB_PASSWORD`                      | `green_password`                  |
| `DB_NAME`                          | `task_engine`                     |
| `MQ_HOST`                          | `localhost`                       |
| `MQ_PORT`                          | `5672`                            |
| `MQ_USER`                          | `green_user`                      |
| `MQ_PASSWORD`                      | `green_password`                  |
| `MQ_SSL_ENABLED`                   | `false`                           |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` |

Worker-specific environment variables:

| Variable                      | Default                                   |
| ----------------------------- | ----------------------------------------- |
| `MQ_HOST`                     | `localhost`                               |
| `MQ_PORT`                     | `5672`                                    |
| `MQ_USER`                     | `green_user`                              |
| `MQ_PASSWORD`                 | `green_password`                          |
| `MQ_SCHEME`                   | `amqp`                                    |
| `WORKER_CONCURRENCY`          | `10`                                      |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | worker telemetry default or Compose value |
| `HTTP_PORT`                   | `8090`                                    |

## Development Commands

### Start and Stop

```sh
docker compose up --build
docker compose down
```

Remove volumes and start from a clean database/broker state:

```sh
docker compose down -v
docker compose up --build
```

### Logs

```sh
docker compose logs -f api-gateway
docker compose logs -f worker-node
docker compose logs -f debezium
docker compose logs -f rabbitmq
```

### Java API Tests

From the API directory:

```sh
cd api-gateway
mvn test
mvn spotless:check
```

### Go Worker Tests

From the worker directory:

```sh
cd worker-node
go test ./...
```

### Load Test

The k6 script lives at [infra/load-tests/k6-script.js](infra/load-tests/k6-script.js).

```sh
k6 run infra/load-tests/k6-script.js
```

Override the API URL:

```sh
API_URL=http://localhost:8080 k6 run infra/load-tests/k6-script.js
```

## Troubleshooting

### API Gateway Fails With `missing table [jobs]`

The API expects Flyway to run before Hibernate schema validation. Rebuild the API image after dependency or migration changes:

```sh
docker compose build api-gateway
docker compose up -d api-gateway
docker compose logs --tail=200 api-gateway
```

Successful startup includes Flyway logs such as:

```text
Successfully applied 1 migration to schema "public"
Started ApiGatewayApplication
```

### Debezium Is Restarting

Check its logs:

```sh
docker compose logs --tail=200 debezium
```

Common causes:

- PostgreSQL is not configured for logical replication.
- The API has not run migrations, so `public.outbox_events` does not exist.
- Debezium offset data in the `debezium_data` volume is stale after schema/config experiments.

For a clean local reset:

```sh
docker compose down -v
docker compose up --build
```

### Job Stays `QUEUED`

Check the async path in order:

```sh
docker compose logs --tail=100 api-gateway
docker compose logs --tail=100 debezium
docker compose logs --tail=100 worker-node
```

Then inspect RabbitMQ:

```text
http://localhost:15672
```

Look at:

- `worker.jobs.queue`
- `worker.jobs.dlq`
- `api.job-results.queue`
- `api.job-results.dlq`

### RabbitMQ UI Login Fails

Use the credentials from `.env` or the defaults:

```text
green_user / green_password
```

If you changed credentials after the RabbitMQ volume already existed, recreate volumes:

```sh
docker compose down -v
docker compose up --build
```

## AWS Deployment Direction

The AWS scaffold is under [infra/terraform/aws](infra/terraform/aws).

The intended deployment target is:

- ECS Fargate service for the API.
- ECS Fargate service for the worker.
- ECS Fargate service for Debezium Server.
- RDS PostgreSQL with logical replication enabled.
- Amazon MQ for RabbitMQ.
- CloudWatch logs.
- IAM roles with least privilege.
- Security groups for service-to-service access.
- OpenTelemetry collector sidecar or gateway for traces and metrics.

Compose remains the local runtime source of truth. Terraform is the future AWS deployment path and is not required for local development.

## Current Limitations

This project is still a learning-oriented proof of concept with production-style boundaries. Important gaps remain:

- No user authentication or authorization.
- No public OpenAPI document for the HTTP API yet.
- Contract schemas are present, but automated schema validation tests are not fully wired across Java and Go.
- Outbox rows are not pruned after successful relay.
- Retry queues with delayed backoff are not fully modeled; current dead-letter handling is basic.
- Terraform is a scaffold, not a complete deployable platform.
- The worker simulates work instead of executing real domain tasks.
- Local credentials are intentionally simple and must be replaced for any shared or deployed environment.

## Design Principles

- Keep the API as the owner of job state.
- Keep workers stateless and database-free.
- Treat RabbitMQ delivery as at-least-once.
- Make event IDs and database constraints the idempotency mechanism.
- Prefer local repeatability over cloud-only behavior.
- Keep container images portable between Compose and ECS.
- Document contracts before expanding behavior.

## License

This project is licensed under the terms in [LICENSE](LICENSE).
