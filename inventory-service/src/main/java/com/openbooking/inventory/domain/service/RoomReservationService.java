package com.openbooking.inventory.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbooking.common.exception.ServiceUnavailableException;
import com.openbooking.inventory.api.dto.ReserveRoomRequest;
import com.openbooking.inventory.api.dto.ReserveRoomResponse;
import com.openbooking.inventory.domain.model.ReserveIdempotency;
import com.openbooking.inventory.domain.model.ReservationHold;
import com.openbooking.inventory.domain.repository.ReservationHoldRepository;
import com.openbooking.inventory.domain.repository.ReserveIdempotencyRepository;
import com.openbooking.inventory.domain.repository.RoomAvailabilityRepository;
import com.openbooking.inventory.domain.strategy.ReservationStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for handling room reservations with concurrency control.
 * 
 * Uses Strategy Pattern with Spring's Map Injection (Factory Pattern):
 * - Spring automatically injects all ReservationStrategy implementations into a Map
 * - Map key is the strategy type (DISTRIBUTED_LOCK, PESSIMISTIC_LOCK, OPTIMISTIC_LOCK)
 * - Strategy selection is done via application.yml configuration (hot-swap without code change)
 * 
 * This approach:
 * - Follows Open/Closed Principle: Open for extension (add new strategies), Closed for modification
 * - Enables hot-swap: Change strategy via config without redeploying code
 * - Leverages Spring's Map injection as a built-in Factory pattern
 * 
 * Strategy implementations (bean names):
 * - distributed: Redis/Redisson distributed locks
 * - pessimistic: Database-level SELECT FOR UPDATE
 * - optimistic: Version-based optimistic locking with retry
 * 
 * Configuration:
 * inventory.reservation.strategy: distributed | pessimistic | optimistic
 * 
 * TODO (Java 21 Migration): Consider replacing ThreadPoolExecutor with Virtual Threads:
 * - Executors.newVirtualThreadPerTaskExecutor() instead of ExecutorService
 * - Simpler concurrency model without manual thread pool management
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomReservationService {

    /**
     * Spring automatically injects all ReservationStrategy implementations into this Map.
     * Map key is the strategy type returned by getStrategyType() method.
     * This acts as a Factory pattern - Spring creates the Map for us.
     */
    private static final String IDEMPOTENCY_UNAVAILABLE_MSG =
            "Idempotency check temporarily unavailable. Retry with same key later.";
    private static final String REDIS_IDEMPOTENCY_PREFIX = "idempotency:reserve:";
    private static final Duration REDIS_IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final Map<String, ReservationStrategy> reservationStrategies;
    private final RoomAvailabilityRepository repository;
    private final ReservationHoldRepository reservationHoldRepository;
    private final ReserveIdempotencyRepository reserveIdempotencyRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Value("${inventory.reservation.strategy:distributed}")
    private String strategyType;

    @Value("${inventory.reservation.hold-ttl-minutes:15}")
    private int holdTtlMinutes;

    @Value("${inventory.reservation.idempotency-redis-cache:true}")
    private boolean idempotencyRedisCacheEnabled;

    @PostConstruct
    public void init() {
        ReservationStrategy strategy = getReservationStrategy();
        log.info("Initialized RoomReservationService with strategy: {}", strategy.getStrategyType());
    }

    /**
     * Reserves a room. Idempotency is stored in DB in the same transaction as reserve:
     * if we fail to save idempotency after reserve, the whole transaction rolls back (no double reserve).
     */
    @Transactional
    public ReserveRoomResponse reserveRoom(ReserveRoomRequest request) {
        String key = request.idempotencyKey();
        if (key != null && !key.isBlank()) {
            Optional<ReserveRoomResponse> cached = getCachedResponse(key);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        ReservationStrategy strategy = getReservationStrategy();
        log.debug("Reserving room using strategy: {}", strategy.getStrategyType());
        ReserveRoomResponse response = strategy.reserve(request);

        if (key != null && !key.isBlank()) {
            saveIdempotencyToDb(key, response);
            warmRedisCache(key, response);
        }
        saveReservationHoldsIfApplicable(request);
        return response;
    }

    /**
     * Read: Redis first (fast) if enabled; on miss or error fall back to DB (source of truth).
     */
    private Optional<ReserveRoomResponse> getCachedResponse(String idempotencyKey) {
        if (idempotencyRedisCacheEnabled && stringRedisTemplate != null) {
            try {
                String json = stringRedisTemplate.opsForValue().get(REDIS_IDEMPOTENCY_PREFIX + idempotencyKey);
                if (json != null) {
                    ReserveRoomResponse r = objectMapper.readValue(json, ReserveRoomResponse.class);
                    log.debug("Idempotency hit from Redis for key: {}", idempotencyKey);
                    return Optional.of(r);
                }
            } catch (Exception e) {
                log.debug("Redis idempotency read missed or failed, falling back to DB: {}", e.getMessage());
            }
        }
        return getCachedResponseFromDb(idempotencyKey);
    }

    /**
     * Best-effort: warm Redis after DB save. Failure does not affect transaction.
     */
    private void warmRedisCache(String idempotencyKey, ReserveRoomResponse response) {
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

    private Optional<ReserveRoomResponse> getCachedResponseFromDb(String idempotencyKey) {
        try {
            return reserveIdempotencyRepository.findById(idempotencyKey)
                    .map(row -> {
                        try {
                            return objectMapper.readValue(row.getResponseJson(), ReserveRoomResponse.class);
                        } catch (JsonProcessingException e) {
                            log.warn("Failed to deserialize cached response for key: {}", idempotencyKey, e);
                            throw new ServiceUnavailableException(IDEMPOTENCY_UNAVAILABLE_MSG, e);
                        }
                    });
        } catch (DataAccessException e) {
            log.warn("Idempotency store (DB) unavailable for key: {}", idempotencyKey, e);
            throw new ServiceUnavailableException(IDEMPOTENCY_UNAVAILABLE_MSG, e);
        }
    }

    private void saveIdempotencyToDb(String idempotencyKey, ReserveRoomResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            reserveIdempotencyRepository.save(new ReserveIdempotency(
                    idempotencyKey, json, LocalDateTime.now()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize response for idempotency key: {}", idempotencyKey, e);
            throw new IllegalStateException("Idempotency save failed", e);
        }
    }

    /**
     * If idempotency key is "booking-{id}", record holds so expiry job can release if not confirmed in time.
     */
    @Transactional
    public void saveReservationHoldsIfApplicable(ReserveRoomRequest request) {
        Long bookingId = parseBookingIdFromIdempotencyKey(request.idempotencyKey());
        if (bookingId == null) return;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(holdTtlMinutes);
        LocalDateTime now = LocalDateTime.now();
        List<LocalDate> dates = generateDateRange(request.checkInDate(), request.checkOutDate());
        for (LocalDate date : dates) {
            ReservationHold hold = ReservationHold.builder()
                    .bookingId(bookingId)
                    .roomId(request.roomId())
                    .availabilityDate(date)
                    .quantity(request.quantity())
                    .expiresAt(expiresAt)
                    .createdAt(now)
                    .build();
            reservationHoldRepository.save(hold);
        }
        log.debug("Saved {} reservation holds for booking {} (expires in {} min)", dates.size(), bookingId, holdTtlMinutes);
    }

    private Long parseBookingIdFromIdempotencyKey(String key) {
        if (key == null || !key.startsWith("booking-")) return null;
        try {
            return Long.parseLong(key.substring("booking-".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Confirm reservation: remove holds for this booking so expiry job does not release.
     * Call after payment success.
     */
    @Transactional
    public void confirmReservation(Long bookingId) {
        int deleted = reservationHoldRepository.deleteByBookingId(bookingId);
        log.info("Confirmed reservation for booking {} (removed {} holds)", bookingId, deleted);
    }
    
    /**
     * Gets the reservation strategy based on configuration.
     * 
     * Spring automatically injects all ReservationStrategy implementations into the Map.
     * The Map key is the Spring bean name (explicitly set via @Component annotation):
     * - distributed
     * - pessimistic
     * - optimistic
     * 
     * This method implements the Factory lookup pattern using Spring's Map injection.
     * Direct lookup by bean name - O(1) complexity instead of O(n) stream filter.
     * Falls back to "distributed" if configured strategy is not found.
     */
    private ReservationStrategy getReservationStrategy() {
        // Convert to lowercase for case-insensitive lookup
        String strategyKey = strategyType.toLowerCase();
        
        // Direct lookup from Map by bean name (O(1) complexity)
        ReservationStrategy strategy = reservationStrategies.get(strategyKey);
        
        if (strategy == null) {
            log.warn("Unknown strategy type: {}. Available strategies: {}. Defaulting to distributed", 
                    strategyType, reservationStrategies.keySet());
            strategy = reservationStrategies.get("distributed");
            
            if (strategy == null) {
                throw new IllegalStateException(
                        "distributed strategy not found. Available strategies: " + 
                        reservationStrategies.keySet());
            }
        }
        
        return strategy;
    }

    /**
     * Releases (returns) reserved rooms back to inventory.
     * Used for compensating transactions in Saga pattern.
     * 
     * @param roomId Room ID
     * @param checkInDate Check-in date
     * @param checkOutDate Check-out date
     * @param quantity Quantity to release
     */
    @Transactional
    public void releaseRoom(Long roomId, LocalDate checkInDate, LocalDate checkOutDate, Integer quantity) {
        releaseRoom(roomId, checkInDate, checkOutDate, quantity, null);
    }

    /**
     * Release reserved rooms (compensating transaction). If bookingId is provided, also deletes holds.
     */
    @Transactional
    public void releaseRoom(Long roomId, LocalDate checkInDate, LocalDate checkOutDate, Integer quantity, Long bookingId) {
        log.info("Releasing room {} for dates {}-{}, quantity: {}", roomId, checkInDate, checkOutDate, quantity);
        if (bookingId != null) {
            reservationHoldRepository.deleteByBookingId(bookingId);
        }
        List<LocalDate> dates = generateDateRange(checkInDate, checkOutDate);
        for (LocalDate date : dates) {
            repository.findByRoomIdAndAvailabilityDate(roomId, date)
                    .ifPresent(availability -> {
                        availability.increaseAvailability(quantity);
                        repository.save(availability);
                        log.debug("Released {} rooms for room {} on date {}", quantity, roomId, date);
                    });
        }
    }

    /**
     * Release expired holds: add back availability and delete holds. Runs on schedule.
     */
    @Scheduled(fixedDelayString = "${inventory.reservation.expiry-job-interval-ms:60000}")
    @Transactional
    public void releaseExpiredHolds() {
        List<ReservationHold> expired = reservationHoldRepository.findExpiredBefore(LocalDateTime.now());
        if (expired.isEmpty()) return;
        for (ReservationHold hold : expired) {
            repository.findByRoomIdAndAvailabilityDate(hold.getRoomId(), hold.getAvailabilityDate())
                    .ifPresent(availability -> {
                        availability.increaseAvailability(hold.getQuantity());
                        repository.save(availability);
                    });
            reservationHoldRepository.delete(hold);
        }
        log.info("Released {} expired reservation holds", expired.size());
    }

    /**
     * Generates list of dates between check-in and check-out (exclusive).
     */
    private List<LocalDate> generateDateRange(LocalDate start, LocalDate end) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate current = start;
        while (current.isBefore(end)) {
            dates.add(current);
            current = current.plusDays(1);
        }
        return dates;
    }
}
