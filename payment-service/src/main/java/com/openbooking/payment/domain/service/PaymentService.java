package com.openbooking.payment.domain.service;

import com.openbooking.common.exception.BusinessException;
import com.openbooking.payment.api.dto.ProcessPaymentRequest;
import com.openbooking.payment.api.dto.ProcessPaymentResponse;
import com.openbooking.payment.domain.model.Payment;
import com.openbooking.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Random;

/**
 * Service for processing payments.
 * This is a mock implementation for demonstration purposes.
 * In production, this would integrate with payment gateways (Stripe, PayPal, etc.).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final Random random = new Random();

    /**
     * Processes a payment transaction.
     * Mock implementation - simulates payment gateway processing.
     * 
     * Note: In a real scenario, this would:
     * 1. Validate payment details
     * 2. Call payment gateway API (Stripe, PayPal, etc.)
     * 3. Handle webhooks for async confirmations
     * 4. Update payment status accordingly
     */
    @Transactional
    public ProcessPaymentResponse processPayment(ProcessPaymentRequest request) {
        log.info("Processing payment for booking ID: {}, amount: {}", 
                request.bookingId(), request.amount());

        Payment payment = Payment.builder()
                .userId(request.userId())
                .bookingId(request.bookingId())
                .amount(request.amount())
                .paymentMethod(request.paymentMethod())
                .status(Payment.PaymentStatus.PENDING)
                .build();

        payment = paymentRepository.save(payment);

        // Mock payment processing - simulate gateway call
        // In production: call external payment gateway API
        boolean success = simulatePaymentGateway(payment);

        if (success) {
            payment.setStatus(Payment.PaymentStatus.SUCCESS);
            payment = paymentRepository.save(payment);
            
            log.info("Payment processed successfully. Payment ID: {}, Transaction ID: {}", 
                    payment.getId(), payment.getTransactionId());
            
            return new ProcessPaymentResponse(
                    payment.getId(),
                    "SUCCESS",
                    "Payment processed successfully",
                    payment.getTransactionId()
            );
        } else {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment = paymentRepository.save(payment);
            
            log.error("Payment processing failed. Payment ID: {}", payment.getId());
            
            return new ProcessPaymentResponse(
                    payment.getId(),
                    "FAILED",
                    "Payment processing failed. Please try again.",
                    payment.getTransactionId()
            );
        }
    }

    /**
     * Mock payment gateway simulation.
     * Returns success with 90% probability for demonstration.
     * In production, this would be replaced with actual payment gateway integration.
     */
    private boolean simulatePaymentGateway(Payment payment) {
        // Simulate network delay
        try {
            Thread.sleep(100 + random.nextInt(200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        // 90% success rate for demo purposes
        return random.nextDouble() > 0.1;
    }
}
