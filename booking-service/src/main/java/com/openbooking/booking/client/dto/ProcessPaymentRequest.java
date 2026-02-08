package com.openbooking.booking.client.dto;

import java.math.BigDecimal;

public record ProcessPaymentRequest(
        Long userId,
        Long bookingId,
        BigDecimal amount,
        String paymentMethod,
        String idempotencyKey
) {
    public ProcessPaymentRequest(Long userId, Long bookingId, BigDecimal amount, String paymentMethod) {
        this(userId, bookingId, amount, paymentMethod, null);
    }
}
