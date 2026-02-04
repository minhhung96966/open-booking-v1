package com.openbooking.booking.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

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
