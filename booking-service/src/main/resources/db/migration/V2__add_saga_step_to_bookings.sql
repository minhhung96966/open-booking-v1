ALTER TABLE bookings ADD COLUMN saga_step VARCHAR(30);
UPDATE bookings SET saga_step = 'CONFIRMED' WHERE status = 'CONFIRMED';
UPDATE bookings SET saga_step = 'FAILED' WHERE status = 'FAILED';
UPDATE bookings SET saga_step = 'PENDING' WHERE saga_step IS NULL;
CREATE INDEX idx_bookings_saga_step_updated ON bookings(saga_step, updated_at);
COMMENT ON COLUMN bookings.saga_step IS 'Saga progress: RESERVE_SENT, RESERVE_OK, PAYMENT_SENT, PAYMENT_OK, CONFIRMED, FAILED';
