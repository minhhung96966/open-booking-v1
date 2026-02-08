CREATE TABLE reservation_holds (
    id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    availability_date DATE NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reservation_holds_booking ON reservation_holds(booking_id);
CREATE INDEX idx_reservation_holds_expires ON reservation_holds(expires_at);

COMMENT ON TABLE reservation_holds IS 'Temporary holds for reserved inventory; expired holds are released by scheduled job';
