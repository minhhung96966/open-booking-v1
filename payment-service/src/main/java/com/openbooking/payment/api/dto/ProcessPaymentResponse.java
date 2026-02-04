package com.openbooking.payment.api.dto;

public record ProcessPaymentResponse(
        Long paymentId,
        String status,
        String message,
        String transactionId
) {
}
