package com.openbooking.inventory.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO for room reservation.
 */
public record ReserveRoomResponse(
        Long reservationId,
        Long roomId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        Integer quantity,
        BigDecimal totalPrice,
        String status
) {
}
