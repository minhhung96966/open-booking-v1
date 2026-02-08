package com.openbooking.inventory.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbooking.common.exception.ServiceUnavailableException;
import com.openbooking.inventory.api.dto.ReserveRoomRequest;
import com.openbooking.inventory.api.dto.ReserveRoomResponse;
import com.openbooking.inventory.domain.model.ReserveIdempotency;
import com.openbooking.inventory.domain.repository.ReservationHoldRepository;
import com.openbooking.inventory.domain.repository.ReserveIdempotencyRepository;
import com.openbooking.inventory.domain.repository.RoomAvailabilityRepository;
import com.openbooking.inventory.domain.strategy.ReservationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for {@link RoomReservationService} – idempotency (DB + optional Redis cache),
 * cache hit/miss, and 503 on idempotency store failure.
 */
@ExtendWith(MockitoExtension.class)
class RoomReservationServiceTest {

    private static final String KEY = "booking-1";
    private static final ReserveRoomResponse CACHED_RESPONSE = new ReserveRoomResponse(
            10L, 101L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 3),
            2, BigDecimal.valueOf(500), "RESERVED"
    );
    private static final ReserveRoomResponse FRESH_RESPONSE = new ReserveRoomResponse(
            11L, 101L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 3),
            2, BigDecimal.valueOf(500), "RESERVED"
    );
    private static final String CACHED_JSON = "{\"reservationId\":10,\"roomId\":101,\"checkInDate\":\"2026-02-01\",\"checkOutDate\":\"2026-02-03\",\"quantity\":2,\"totalPrice\":500,\"status\":\"RESERVED\"}";

    @Mock
    private Map<String, ReservationStrategy> reservationStrategies;
    @Mock
    private ReservationStrategy strategy;
    @Mock
    private RoomAvailabilityRepository repository;
    @Mock
    private ReservationHoldRepository reservationHoldRepository;
    @Mock
    private ReserveIdempotencyRepository reserveIdempotencyRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private RoomReservationService service;

    @BeforeEach
    void setUp() {
        // lenient: strategy/Redis stubs not used in cache-hit or Redis-disabled tests
        lenient().when(reservationStrategies.get("distributed")).thenReturn(strategy);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        service = new RoomReservationService(
                reservationStrategies,
                repository,
                reservationHoldRepository,
                reserveIdempotencyRepository,
                objectMapper
        );
        ReflectionTestUtils.setField(service, "strategyType", "distributed");
        ReflectionTestUtils.setField(service, "holdTtlMinutes", 15);
        ReflectionTestUtils.setField(service, "idempotencyRedisCacheEnabled", true);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    @DisplayName("reserveRoom without idempotency key: strategy.reserve() called, no idempotency save")
    void reserveRoom_noKey_callsStrategyNoIdempotency() {
        ReserveRoomRequest request = new ReserveRoomRequest(101L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 3), 2, null);
        when(strategy.reserve(request)).thenReturn(FRESH_RESPONSE);

        ReserveRoomResponse result = service.reserveRoom(request);

        assertThat(result).isEqualTo(FRESH_RESPONSE);
        verify(strategy).reserve(request);
        verify(reserveIdempotencyRepository, never()).save(any());
        verify(stringRedisTemplate.opsForValue(), never()).set(any(), any(), any());
    }

    @Test
    @DisplayName("reserveRoom with idempotency key, cache miss: strategy called, idempotency saved, Redis warmed")
    void reserveRoom_withKey_cacheMiss_callsStrategySavesIdempotencyWarmsRedis() throws JsonProcessingException {
        ReserveRoomRequest request = new ReserveRoomRequest(101L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 3), 2, KEY);
        when(strategy.reserve(request)).thenReturn(FRESH_RESPONSE);
        when(reserveIdempotencyRepository.findById(KEY)).thenReturn(Optional.empty());
        when(objectMapper.writeValueAsString(FRESH_RESPONSE)).thenReturn(CACHED_JSON);

        ReserveRoomResponse result = service.reserveRoom(request);

        assertThat(result).isEqualTo(FRESH_RESPONSE);
        verify(strategy).reserve(request);
        verify(reserveIdempotencyRepository).save(argThat(e ->
                e.getIdempotencyKey().equals(KEY) && e.getResponseJson().equals(CACHED_JSON)));
        verify(valueOps).set(eq("idempotency:reserve:" + KEY), eq(CACHED_JSON), any());
    }

    @Test
    @DisplayName("reserveRoom with idempotency key, cache hit from DB: returns cached, strategy not called")
    void reserveRoom_withKey_cacheHitFromDb_returnsCached() throws JsonProcessingException {
        ReserveRoomRequest request = new ReserveRoomRequest(101L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 3), 2, KEY);
        when(valueOps.get("idempotency:reserve:" + KEY)).thenReturn(null);
        ReserveIdempotency stored = new ReserveIdempotency(KEY, CACHED_JSON, LocalDateTime.now());
        when(reserveIdempotencyRepository.findById(KEY)).thenReturn(Optional.of(stored));
        when(objectMapper.readValue(CACHED_JSON, ReserveRoomResponse.class)).thenReturn(CACHED_RESPONSE);

        ReserveRoomResponse result = service.reserveRoom(request);

        assertThat(result).isEqualTo(CACHED_RESPONSE);
        verify(strategy, never()).reserve(any());
        verify(reserveIdempotencyRepository, never()).save(any());
    }

    @Test
    @DisplayName("reserveRoom with idempotency key, cache hit from Redis: returns cached, strategy not called")
    void reserveRoom_withKey_cacheHitFromRedis_returnsCached() throws JsonProcessingException {
        ReserveRoomRequest request = new ReserveRoomRequest(101L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 3), 2, KEY);
        when(valueOps.get("idempotency:reserve:" + KEY)).thenReturn(CACHED_JSON);
        when(objectMapper.readValue(CACHED_JSON, ReserveRoomResponse.class)).thenReturn(CACHED_RESPONSE);

        ReserveRoomResponse result = service.reserveRoom(request);

        assertThat(result).isEqualTo(CACHED_RESPONSE);
        verify(strategy, never()).reserve(any());
        verify(reserveIdempotencyRepository, never()).findById(any());
        verify(reserveIdempotencyRepository, never()).save(any());
    }

    @Test
    @DisplayName("reserveRoom with idempotency key, DB read throws DataAccessException: throws ServiceUnavailableException")
    void reserveRoom_withKey_dbReadFails_throws503() {
        ReserveRoomRequest request = new ReserveRoomRequest(101L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 3), 2, KEY);
        when(valueOps.get("idempotency:reserve:" + KEY)).thenReturn(null);
        when(reserveIdempotencyRepository.findById(KEY)).thenThrow(new org.springframework.dao.DataRetrievalFailureException("DB down"));

        assertThatThrownBy(() -> service.reserveRoom(request))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Idempotency check temporarily unavailable");
        verify(strategy, never()).reserve(any());
    }

    @Test
    @DisplayName("Redis disabled: cache hit only from DB")
    void reserveRoom_redisDisabled_cacheHitFromDb() throws JsonProcessingException {
        ReflectionTestUtils.setField(service, "idempotencyRedisCacheEnabled", false);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", null);

        ReserveRoomRequest request = new ReserveRoomRequest(101L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 3), 2, KEY);
        ReserveIdempotency stored = new ReserveIdempotency(KEY, CACHED_JSON, LocalDateTime.now());
        when(reserveIdempotencyRepository.findById(KEY)).thenReturn(Optional.of(stored));
        when(objectMapper.readValue(CACHED_JSON, ReserveRoomResponse.class)).thenReturn(CACHED_RESPONSE);

        ReserveRoomResponse result = service.reserveRoom(request);

        assertThat(result).isEqualTo(CACHED_RESPONSE);
        verify(strategy, never()).reserve(any());
    }
}
