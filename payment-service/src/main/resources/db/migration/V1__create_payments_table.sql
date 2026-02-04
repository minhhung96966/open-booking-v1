CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    booking_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL CHECK (amount > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_method VARCHAR(50) NOT NULL,
    transaction_id VARCHAR(100) UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_id ON payments(user_id);
CREATE INDEX idx_booking_id ON payments(booking_id);
CREATE INDEX idx_status ON payments(status);
CREATE UNIQUE INDEX idx_transaction_id ON payments(transaction_id);

COMMENT ON TABLE payments IS 'Stores payment transactions';
COMMENT ON COLUMN payments.status IS 'Payment status: PENDING, SUCCESS, FAILED, REFUNDED';
