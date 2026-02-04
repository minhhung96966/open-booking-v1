package com.openbooking.inventory.domain.strategy;

import com.openbooking.common.exception.BusinessException;
import com.openbooking.inventory.api.dto.ReserveRoomRequest;
import com.openbooking.inventory.api.dto.ReserveRoomResponse;
import com.openbooking.inventory.domain.model.RoomAvailability;
import com.openbooking.inventory.domain.repository.RoomAvailabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reservation strategy using optimistic lock with retry mechanism.
 * 
 * Uses version-based conflict detection (@Version field in entity).
 * Retries on OptimisticLockingFailureException up to 3 times.
 * 
 * Benefits:
 * - No deadlocks
 * - Lightweight
 * - Good for moderate concurrency
 * 
 * Flow:
 * 1. Read availability with version
 * 2. Check availability
 * 3. Decrease availability (version checked automatically)
 * 4. Save (throws OptimisticLockingFailureException if version changed)
 * 5. Retry if exception occurs
 */
@Slf4j
@Component("optimistic")
@RequiredArgsConstructor
public class OptimisticLockReservationStrategy implements ReservationStrategy {

    private final RoomAvailabilityRepository repository;

    @Override
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional
    public ReserveRoomResponse reserve(ReserveRoomRequest request) {
        List<LocalDate> dates = generateDateRange(request.checkInDate(), request.checkOutDate());
        
        // Process each date with optimistic lock
        for (LocalDate date : dates) {
            RoomAvailability availability = repository
                    .findByRoomIdAndAvailabilityDate(request.roomId(), date)
                    .orElseThrow(() -> new BusinessException(
                            String.format("Room %d not available for date %s", request.roomId(), date)));

            if (availability.getAvailableCount() < request.quantity()) {
                throw new BusinessException(
                        String.format("Insufficient availability for room %d on %s", request.roomId(), date));
            }

            // This will throw OptimisticLockingFailureException if version changed
            availability.decreaseAvailability(request.quantity());
            repository.save(availability);
        }

        BigDecimal totalPrice = calculateTotalPrice(request);
        return new ReserveRoomResponse(
                System.currentTimeMillis(),
                request.roomId(),
                request.checkInDate(),
                request.checkOutDate(),
                request.quantity(),
                totalPrice,
                "RESERVED"
        );
    }

    @Override
    public String getStrategyType() {
        return "OPTIMISTIC_LOCK";
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

    /**
     * Calculates total price for the reservation.
     */
    private BigDecimal calculateTotalPrice(ReserveRoomRequest request) {
        List<LocalDate> dates = generateDateRange(request.checkInDate(), request.checkOutDate());
        BigDecimal total = BigDecimal.ZERO;
        
        for (LocalDate date : dates) {
            RoomAvailability availability = repository
                    .findByRoomIdAndAvailabilityDate(request.roomId(), date)
                    .orElseThrow();
            total = total.add(availability.getPricePerNight().multiply(BigDecimal.valueOf(request.quantity())));
        }
        
        return total;
    }
}
