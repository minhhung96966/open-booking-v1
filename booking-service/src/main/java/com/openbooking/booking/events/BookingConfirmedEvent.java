package com.openbooking.booking.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Event published when a booking is confirmed.
 * Consumed by notification-service and catalog-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingConfirmedEvent {
    private Long bookingId;
    private Long userId;
    private Long roomId;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private BigDecimal totalPrice;
    private String status;
    private Instant timestamp;
    /** True when confirmed by recovery job (user may have seen error earlier). Notification can advise to cancel duplicate if they booked elsewhere. */
    private boolean recoveryConfirmed;
}
