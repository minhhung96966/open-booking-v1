package com.openbooking.booking.client.dto;

import java.time.LocalDate;

public record ReserveRoomRequest(
        Long roomId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        Integer quantity
) {
}
