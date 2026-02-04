package com.openbooking.payment.api.controller;

import com.openbooking.common.dto.BaseResponse;
import com.openbooking.payment.api.dto.ProcessPaymentRequest;
import com.openbooking.payment.api.dto.ProcessPaymentResponse;
import com.openbooking.payment.domain.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for payment operations.
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/process")
    public ResponseEntity<BaseResponse<ProcessPaymentResponse>> processPayment(
            @Valid @RequestBody ProcessPaymentRequest request) {
        ProcessPaymentResponse response = paymentService.processPayment(request);
        return ResponseEntity.ok(BaseResponse.success("Payment processed", response));
    }
}
