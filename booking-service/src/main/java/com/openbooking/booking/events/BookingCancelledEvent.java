package com.openbooking.booking.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Event published when a booking is cancelled.
 * Consumed by notification-service and inventory-service (for releasing rooms).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingCancelledEvent {
    private Long bookingId;
    private Long userId;
    private Long roomId;
    private String reason;
    private Instant timestamp;
}
