package com.openbooking.payment.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ProcessPaymentRequest(
        @NotNull(message = "User ID cannot be null")
        Long userId,

        @NotNull(message = "Booking ID cannot be null")
        Long bookingId,

        @Positive(message = "Amount must be positive")
        @NotNull(message = "Amount cannot be null")
        BigDecimal amount,

        @NotNull(message = "Payment method cannot be null")
        String paymentMethod
) {
}
