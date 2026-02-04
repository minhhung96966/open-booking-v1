package com.openbooking.booking.api.dto;

import com.openbooking.booking.domain.model.Booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record BookingResponse(
        Long id,
        Long userId,
        Long roomId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        Integer quantity,
        BigDecimal totalPrice,
        Booking.BookingStatus status,
        Long paymentId,
        LocalDateTime createdAt
) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getUserId(),
                booking.getRoomId(),
                booking.getCheckInDate(),
                booking.getCheckOutDate(),
                booking.getQuantity(),
                booking.getTotalPrice(),
                booking.getStatus(),
                booking.getPaymentId(),
                booking.getCreatedAt()
        );
    }
}
