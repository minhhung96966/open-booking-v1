package com.openbooking.booking.client.dto;

public record ProcessPaymentResponse(
        Long paymentId,
        String status,
        String message
) {
}
