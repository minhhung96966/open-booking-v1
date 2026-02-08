package com.openbooking.payment.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbooking.common.exception.ServiceUnavailableException;
import com.openbooking.payment.api.dto.ProcessPaymentRequest;
import com.openbooking.payment.api.dto.ProcessPaymentResponse;
import com.openbooking.payment.domain.model.IdempotencyStore;
import com.openbooking.payment.domain.model.Payment;
import com.openbooking.payment.domain.repository.IdempotencyStoreRepository;
import com.openbooking.payment.domain.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for {@link PaymentService} – idempotency (DB + optional Redis cache),
 * cache hit/miss, and 503 on idempotency store failure.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String KEY = "booking-1";
    private static final ProcessPaymentResponse CACHED_RESPONSE = new ProcessPaymentResponse(
            10L, "SUCCESS", "Payment processed successfully", "tx-10"
    );
    private static final String CACHED_JSON = "{\"paymentId\":10,\"status\":\"SUCCESS\",\"message\":\"OK\",\"transactionId\":\"tx-10\"}";

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private IdempotencyStoreRepository idempotencyStoreRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentRepository, idempotencyStoreRepository, objectMapper);
        ReflectionTestUtils.setField(service, "idempotencyRedisCacheEnabled", true);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        // lenient: Redis stubs not used when no idempotency key or when Redis disabled
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        // Deterministic "success" for simulatePaymentGateway: first nextDouble() > 0.1
        ReflectionTestUtils.setField(service, "random", new java.util.Random(999));
    }

    @Test
    @DisplayName("processPayment without idempotency key: processes and does not save idempotency")
    void processPayment_noKey_processesNoIdempotency() {
        ProcessPaymentRequest request = new ProcessPaymentRequest(100L, 1L, BigDecimal.valueOf(200), "CREDIT_CARD", null);
        Payment saved = Payment.builder()
                .id(5L).userId(100L).bookingId(1L).amount(BigDecimal.valueOf(200))
                .paymentMethod("CREDIT_CARD").status(Payment.PaymentStatus.PENDING)
                .transactionId("tx-5").build();
        when(paymentRepository.save(any(Payment.class))).thenReturn(saved);

        ProcessPaymentResponse result = service.processPayment(request);

        assertThat(result.paymentId()).isEqualTo(5L);
        assertThat(result.transactionId()).isEqualTo("tx-5");
        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
        verify(idempotencyStoreRepository, never()).save(any());
        verify(valueOps, never()).set(any(), any(), any());
    }

    @Test
    @DisplayName("processPayment with idempotency key, cache miss: processes, saves idempotency, warms Redis")
    void processPayment_withKey_cacheMiss_savesIdempotencyWarmsRedis() throws JsonProcessingException {
        ProcessPaymentRequest request = new ProcessPaymentRequest(100L, 1L, BigDecimal.valueOf(200), "CREDIT_CARD", KEY);
        when(valueOps.get("idempotency:payment:" + KEY)).thenReturn(null);
        when(idempotencyStoreRepository.findById(KEY)).thenReturn(Optional.empty());
        Payment saved = Payment.builder()
                .id(5L).userId(100L).bookingId(1L).amount(BigDecimal.valueOf(200))
                .paymentMethod("CREDIT_CARD").status(Payment.PaymentStatus.SUCCESS)
                .transactionId("tx-5").build();
        when(paymentRepository.save(any(Payment.class))).thenReturn(saved);
        when(objectMapper.writeValueAsString(any(ProcessPaymentResponse.class))).thenReturn(CACHED_JSON);

        ProcessPaymentResponse result = service.processPayment(request);

        assertThat(result.paymentId()).isEqualTo(5L);
        verify(idempotencyStoreRepository).save(argThat(e ->
                e.getIdempotencyKey().equals(KEY) && e.getResponseJson().equals(CACHED_JSON)));
        verify(valueOps).set(eq("idempotency:payment:" + KEY), eq(CACHED_JSON), any());
    }

    @Test
    @DisplayName("processPayment with idempotency key, cache hit from DB: returns cached, no new payment")
    void processPayment_withKey_cacheHitFromDb_returnsCached() throws JsonProcessingException {
        ProcessPaymentRequest request = new ProcessPaymentRequest(100L, 1L, BigDecimal.valueOf(200), "CREDIT_CARD", KEY);
        when(valueOps.get("idempotency:payment:" + KEY)).thenReturn(null);
        IdempotencyStore stored = new IdempotencyStore(KEY, CACHED_JSON, LocalDateTime.now());
        when(idempotencyStoreRepository.findById(KEY)).thenReturn(Optional.of(stored));
        when(objectMapper.readValue(CACHED_JSON, ProcessPaymentResponse.class)).thenReturn(CACHED_RESPONSE);

        ProcessPaymentResponse result = service.processPayment(request);

        assertThat(result).isEqualTo(CACHED_RESPONSE);
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(idempotencyStoreRepository, never()).save(any());
    }

    @Test
    @DisplayName("processPayment with idempotency key, cache hit from Redis: returns cached")
    void processPayment_withKey_cacheHitFromRedis_returnsCached() throws JsonProcessingException {
        ProcessPaymentRequest request = new ProcessPaymentRequest(100L, 1L, BigDecimal.valueOf(200), "CREDIT_CARD", KEY);
        when(valueOps.get("idempotency:payment:" + KEY)).thenReturn(CACHED_JSON);
        when(objectMapper.readValue(CACHED_JSON, ProcessPaymentResponse.class)).thenReturn(CACHED_RESPONSE);

        ProcessPaymentResponse result = service.processPayment(request);

        assertThat(result).isEqualTo(CACHED_RESPONSE);
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(idempotencyStoreRepository, never()).findById(any());
        verify(idempotencyStoreRepository, never()).save(any());
    }

    @Test
    @DisplayName("processPayment with idempotency key, DB read throws: throws ServiceUnavailableException")
    void processPayment_withKey_dbReadFails_throws503() {
        ProcessPaymentRequest request = new ProcessPaymentRequest(100L, 1L, BigDecimal.valueOf(200), "CREDIT_CARD", KEY);
        when(valueOps.get("idempotency:payment:" + KEY)).thenReturn(null);
        when(idempotencyStoreRepository.findById(KEY)).thenThrow(new DataRetrievalFailureException("DB down"));

        assertThatThrownBy(() -> service.processPayment(request))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Idempotency check temporarily unavailable");
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("Redis disabled: cache hit only from DB")
    void processPayment_redisDisabled_cacheHitFromDb() throws JsonProcessingException {
        ReflectionTestUtils.setField(service, "idempotencyRedisCacheEnabled", false);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", null);

        ProcessPaymentRequest request = new ProcessPaymentRequest(100L, 1L, BigDecimal.valueOf(200), "CREDIT_CARD", KEY);
        IdempotencyStore stored = new IdempotencyStore(KEY, CACHED_JSON, LocalDateTime.now());
        when(idempotencyStoreRepository.findById(KEY)).thenReturn(Optional.of(stored));
        when(objectMapper.readValue(CACHED_JSON, ProcessPaymentResponse.class)).thenReturn(CACHED_RESPONSE);

        ProcessPaymentResponse result = service.processPayment(request);

        assertThat(result).isEqualTo(CACHED_RESPONSE);
        verify(paymentRepository, never()).save(any(Payment.class));
    }
}
