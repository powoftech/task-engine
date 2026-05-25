CREATE TYPE job_status AS ENUM ('PENDING', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED');

CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    client_request_id VARCHAR(128),
    task_type VARCHAR(255) NOT NULL,
    complexity INTEGER NOT NULL CHECK (complexity >= 1 AND complexity <= 10),
    status job_status NOT NULL DEFAULT 'PENDING',
    result JSONB,
    failure_message TEXT,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX ux_jobs_client_request_id
    ON jobs(client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE INDEX idx_jobs_status ON jobs(status);

CREATE OR REPLACE FUNCTION update_modified_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_jobs_modtime
    BEFORE UPDATE ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION update_modified_column();

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_id VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id, aggregate_type);
CREATE INDEX idx_outbox_type_created_at ON outbox_events(type, created_at);

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
