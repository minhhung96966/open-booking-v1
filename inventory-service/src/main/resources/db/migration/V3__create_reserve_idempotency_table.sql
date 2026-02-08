CREATE TABLE reserve_idempotency (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reserve_idempotency_created ON reserve_idempotency(created_at);
COMMENT ON TABLE reserve_idempotency IS 'Idempotency for reserve: same transaction as reserve, no double reserve when DB/Redis fails after reserve';
