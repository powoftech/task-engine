-- Create the ENUM type for job statuses
CREATE TYPE job_status AS ENUM ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED');

-- Create the jobs table
CREATE TABLE IF NOT EXISTS jobs (
    -- id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    id UUID PRIMARY KEY,
    task_type VARCHAR(255) NOT NULL,
    status job_status NOT NULL DEFAULT 'PENDING',
    result JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create an index on status for faster querying by worker nodes
CREATE INDEX idx_jobs_status ON jobs(status);

-- Function to automatically update the updated_at timestamp
CREATE OR REPLACE FUNCTION update_modified_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Trigger to fire the updated_at function
CREATE TRIGGER update_jobs_modtime
    BEFORE UPDATE ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION update_modified_column();

-- Create ENUM for outbox event status
CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED');

-- Create outbox events table
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    status outbox_status NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE
);

-- Index for efficient polling of pending events (FIFO order)
CREATE INDEX idx_outbox_status_created ON outbox_events(status, created_at)
WHERE status IN ('PENDING', 'FAILED');

-- Index for lookup by aggregate
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id, aggregate_type);