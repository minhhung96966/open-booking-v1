package com.openbooking.inventory.domain.strategy;

import com.openbooking.common.exception.BusinessException;
import com.openbooking.inventory.api.dto.ReserveRoomRequest;
import com.openbooking.inventory.api.dto.ReserveRoomResponse;
import com.openbooking.inventory.domain.model.RoomAvailability;
import com.openbooking.inventory.domain.repository.RoomAvailabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reservation strategy using pessimistic lock (SELECT FOR UPDATE).
 * 
 * Use this when you need database-level exclusive access.
 * Provides strong consistency but can cause deadlocks in high concurrency scenarios.
 * 
 * Flow:
 * 1. Acquire database-level lock (SELECT FOR UPDATE)
 * 2. Check availability
 * 3. Decrease availability count
 * 4. Commit transaction (releases lock)
 */
@Slf4j
@Component("pessimistic")
@RequiredArgsConstructor
public class PessimisticLockReservationStrategy implements ReservationStrategy {

    private final RoomAvailabilityRepository repository;

    @Override
    @Transactional
    public ReserveRoomResponse reserve(ReserveRoomRequest request) {
        List<LocalDate> dates = generateDateRange(request.checkInDate(), request.checkOutDate());
        
        // Process each date with pessimistic lock
        for (LocalDate date : dates) {
            RoomAvailability availability = repository
                    .findByRoomIdAndDateWithLock(request.roomId(), date)
                    .orElseThrow(() -> new BusinessException(
                            String.format("Room %d not available for date %s", request.roomId(), date)));

            if (availability.getAvailableCount() < request.quantity()) {
                throw new BusinessException(
                        String.format("Insufficient availability for room %d on %s", request.roomId(), date));
            }

            availability.decreaseAvailability(request.quantity());
            repository.save(availability);
        }

        BigDecimal totalPrice = calculateTotalPrice(request);
        return new ReserveRoomResponse(
                System.currentTimeMillis(), // Mock reservation ID
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
        return "PESSIMISTIC_LOCK";
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
