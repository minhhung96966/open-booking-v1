CREATE TABLE room_availability (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL,
    availability_date DATE NOT NULL,
    available_count INTEGER NOT NULL CHECK (available_count >= 0),
    price_per_night DECIMAL(10, 2) NOT NULL CHECK (price_per_night >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(room_id, availability_date)
);

CREATE INDEX idx_room_date ON room_availability(room_id, availability_date);
CREATE INDEX idx_date ON room_availability(availability_date);

COMMENT ON TABLE room_availability IS 'Stores room availability and pricing per day';
COMMENT ON COLUMN room_availability.version IS 'Version field for optimistic locking';
