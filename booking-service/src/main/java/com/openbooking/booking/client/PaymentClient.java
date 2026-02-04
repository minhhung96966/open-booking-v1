package com.openbooking.booking.client;

import com.openbooking.booking.client.dto.ProcessPaymentRequest;
import com.openbooking.booking.client.dto.ProcessPaymentResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Feign client for communicating with Payment Service.
 * Used in Saga Orchestration for processing payments.
 */
@FeignClient(name = "payment-service", path = "/api/v1/payments")
public interface PaymentClient {

    @PostMapping("/process")
    ProcessPaymentResponse processPayment(@RequestBody ProcessPaymentRequest request);
}
