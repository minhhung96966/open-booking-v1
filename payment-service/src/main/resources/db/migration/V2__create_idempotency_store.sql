CREATE TABLE idempotency_store (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_idempotency_created_at ON idempotency_store(created_at);
COMMENT ON TABLE idempotency_store IS 'Cached payment responses by idempotency key (TTL handled by app or cleanup job)';
