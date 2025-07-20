-- Create outbox_messages table for transactional outbox pattern
CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    changed_fields TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    error_message TEXT,
    worker_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0
);

-- Create indexes for efficient querying
CREATE INDEX idx_outbox_status_created ON outbox_messages (status, created_at);
CREATE INDEX idx_outbox_aggregate_id ON outbox_messages (aggregate_id);
CREATE INDEX idx_outbox_event_type ON outbox_messages (event_type);
CREATE INDEX idx_outbox_worker_id ON outbox_messages (worker_id) WHERE worker_id IS NOT NULL;

-- Add comments for documentation
COMMENT ON TABLE outbox_messages IS 'Stores outbox messages for the transactional outbox pattern';
COMMENT ON COLUMN outbox_messages.id IS 'Unique identifier for the outbox message';
COMMENT ON COLUMN outbox_messages.aggregate_id IS 'ID of the aggregate root that triggered the event';
COMMENT ON COLUMN outbox_messages.aggregate_type IS 'Type of the aggregate root (e.g., User, Order)';
COMMENT ON COLUMN outbox_messages.event_type IS 'Type of event (e.g., USER_CREATED, ORDER_UPDATED)';
COMMENT ON COLUMN outbox_messages.payload IS 'JSON payload containing the event data';
COMMENT ON COLUMN outbox_messages.changed_fields IS 'JSON representation of fields that changed (for updates)';
COMMENT ON COLUMN outbox_messages.status IS 'Current status: PENDING, SENT, FAILED, DEAD_LETTER';
COMMENT ON COLUMN outbox_messages.created_at IS 'Timestamp when the message was created';
COMMENT ON COLUMN outbox_messages.processed_at IS 'Timestamp when the message was processed';
COMMENT ON COLUMN outbox_messages.retry_count IS 'Number of retry attempts made';
COMMENT ON COLUMN outbox_messages.max_retries IS 'Maximum number of retry attempts allowed';
COMMENT ON COLUMN outbox_messages.error_message IS 'Error message from the last failed attempt';
COMMENT ON COLUMN outbox_messages.worker_id IS 'ID of the worker processing this message';
COMMENT ON COLUMN outbox_messages.version IS 'Optimistic locking version'; 