package com.openbooking.inventory.domain.service;

import com.openbooking.inventory.api.dto.ReserveRoomRequest;
import com.openbooking.inventory.api.dto.ReserveRoomResponse;
import com.openbooking.inventory.domain.repository.RoomAvailabilityRepository;
import com.openbooking.inventory.domain.strategy.ReservationStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final Map<String, ReservationStrategy> reservationStrategies;
    private final RoomAvailabilityRepository repository;
    
    @Value("${inventory.reservation.strategy:distributed}")
    private String strategyType;

    @PostConstruct
    public void init() {
        ReservationStrategy strategy = getReservationStrategy();
        log.info("Initialized RoomReservationService with strategy: {}", strategy.getStrategyType());
    }

    /**
     * Reserves a room using the configured reservation strategy.
     * Strategy is selected via application.yml property: inventory.reservation.strategy
     * 
     * Hot-swap capability: Change strategy in config and restart application.
     * No code modification needed.
     * 
     * @param request Reservation request
     * @return Reservation response
     */
    public ReserveRoomResponse reserveRoom(ReserveRoomRequest request) {
        ReservationStrategy strategy = getReservationStrategy();
        log.debug("Reserving room using strategy: {}", strategy.getStrategyType());
        return strategy.reserve(request);
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
        log.info("Releasing room {} for dates {}-{}, quantity: {}", roomId, checkInDate, checkOutDate, quantity);
        
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
