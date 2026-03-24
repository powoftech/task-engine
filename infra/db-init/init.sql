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

-- Create outbox events table
-- Debezium CDC captures inserts from this table and publishes to RabbitMQ
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    payload JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index for lookup by aggregate
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id, aggregate_type);
