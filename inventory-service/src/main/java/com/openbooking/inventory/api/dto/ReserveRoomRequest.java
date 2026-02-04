package com.openbooking.inventory.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/**
 * Request DTO for reserving a room.
 */
public record ReserveRoomRequest(
        @NotNull(message = "Room ID cannot be null")
        Long roomId,

        @NotNull(message = "Check-in date cannot be null")
        LocalDate checkInDate,

        @NotNull(message = "Check-out date cannot be null")
        LocalDate checkOutDate,

        @Positive(message = "Quantity must be positive")
        @NotNull(message = "Quantity cannot be null")
        Integer quantity
) {
}
