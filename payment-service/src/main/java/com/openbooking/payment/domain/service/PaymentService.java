package com.openbooking.payment.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbooking.common.exception.BusinessException;
import com.openbooking.common.exception.ServiceUnavailableException;
import com.openbooking.payment.api.dto.ProcessPaymentRequest;
import com.openbooking.payment.api.dto.ProcessPaymentResponse;
import com.openbooking.payment.domain.model.IdempotencyStore;
import com.openbooking.payment.domain.model.Payment;
import com.openbooking.payment.domain.repository.IdempotencyStoreRepository;
import com.openbooking.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
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

    private static final String REDIS_IDEMPOTENCY_PREFIX = "idempotency:payment:";
    private static final Duration REDIS_IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final PaymentRepository paymentRepository;
    private final IdempotencyStoreRepository idempotencyStoreRepository;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Value("${payment.idempotency-redis-cache:true}")
    private boolean idempotencyRedisCacheEnabled;

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
        String key = request.idempotencyKey();
        if (key != null && !key.isBlank()) {
            ProcessPaymentResponse cached = getCachedResponse(key);
            if (cached != null) {
                return cached;
            }
        }

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

        ProcessPaymentResponse response;
        if (success) {
            payment.setStatus(Payment.PaymentStatus.SUCCESS);
            payment = paymentRepository.save(payment);
            log.info("Payment processed successfully. Payment ID: {}, Transaction ID: {}", 
                    payment.getId(), payment.getTransactionId());
            response = new ProcessPaymentResponse(
                    payment.getId(),
                    "SUCCESS",
                    "Payment processed successfully",
                    payment.getTransactionId()
            );
        } else {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment = paymentRepository.save(payment);
            log.error("Payment processing failed. Payment ID: {}", payment.getId());
            response = new ProcessPaymentResponse(
                    payment.getId(),
                    "FAILED",
                    "Payment processing failed. Please try again.",
                    payment.getTransactionId()
            );
        }

        if (key != null && !key.isBlank()) {
            try {
                String responseJson = objectMapper.writeValueAsString(response);
                idempotencyStoreRepository.save(new IdempotencyStore(key, responseJson, LocalDateTime.now()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to cache response for idempotency key: {}", key, e);
            }
            warmRedisCache(key, response);
        }
        return response;
    }

    /**
     * Read: Redis first (fast) if enabled; on miss or error fall back to DB (source of truth).
     */
    private ProcessPaymentResponse getCachedResponse(String idempotencyKey) {
        if (idempotencyRedisCacheEnabled && stringRedisTemplate != null) {
            try {
                String json = stringRedisTemplate.opsForValue().get(REDIS_IDEMPOTENCY_PREFIX + idempotencyKey);
                if (json != null) {
                    ProcessPaymentResponse r = objectMapper.readValue(json, ProcessPaymentResponse.class);
                    log.debug("Idempotency hit from Redis for key: {}", idempotencyKey);
                    return r;
                }
            } catch (Exception e) {
                log.debug("Redis idempotency read missed or failed, falling back to DB: {}", e.getMessage());
            }
        }
        try {
            var cached = idempotencyStoreRepository.findById(idempotencyKey);
            if (cached.isPresent()) {
                ProcessPaymentResponse response = objectMapper.readValue(
                        cached.get().getResponseJson(), ProcessPaymentResponse.class);
                log.debug("Idempotency hit from DB for key: {}", idempotencyKey);
                return response;
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached response for key: {}", idempotencyKey, e);
            throw new ServiceUnavailableException(
                    "Idempotency check temporarily unavailable. Retry with same key later.", e);
        } catch (DataAccessException e) {
            log.warn("Idempotency store (DB) unavailable for key: {}", idempotencyKey, e);
            throw new ServiceUnavailableException(
                    "Idempotency check temporarily unavailable. Retry with same key later.", e);
        }
        return null;
    }

    /** Best-effort: warm Redis after DB save. Failure does not affect transaction. */
    private void warmRedisCache(String idempotencyKey, ProcessPaymentResponse response) {
        if (!idempotencyRedisCacheEnabled || stringRedisTemplate == null) return;
        try {
            String json = objectMapper.writeValueAsString(response);
            stringRedisTemplate.opsForValue().set(
                    REDIS_IDEMPOTENCY_PREFIX + idempotencyKey, json, REDIS_IDEMPOTENCY_TTL);
            log.debug("Warmed Redis idempotency cache for key: {}", idempotencyKey);
        } catch (Exception e) {
            log.warn("Failed to warm Redis idempotency cache for key: {} (non-fatal)", idempotencyKey, e);
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
