CREATE TABLE bookings (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    check_in_date DATE NOT NULL,
    check_out_date DATE NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    total_price DECIMAL(10, 2) NOT NULL CHECK (total_price >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CHECK (check_out_date > check_in_date)
);

CREATE INDEX idx_user_id ON bookings(user_id);
CREATE INDEX idx_status ON bookings(status);
CREATE INDEX idx_created_at ON bookings(created_at);

COMMENT ON TABLE bookings IS 'Stores hotel room bookings';
COMMENT ON COLUMN bookings.status IS 'Booking status: PENDING, CONFIRMED, CANCELLED, FAILED';
