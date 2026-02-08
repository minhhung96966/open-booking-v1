package com.openbooking.booking.client.dto;

import java.time.LocalDate;

public record ReserveRoomRequest(
        Long roomId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        Integer quantity,
        String idempotencyKey
) {
    public ReserveRoomRequest(Long roomId, LocalDate checkInDate, LocalDate checkOutDate, Integer quantity) {
        this(roomId, checkInDate, checkOutDate, quantity, null);
    }
}
