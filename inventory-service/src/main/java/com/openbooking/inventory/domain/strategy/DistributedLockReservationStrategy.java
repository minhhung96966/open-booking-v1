package com.openbooking.inventory.domain.strategy;

import com.openbooking.common.exception.BusinessException;
import com.openbooking.common.util.Constants;
import com.openbooking.inventory.api.dto.ReserveRoomRequest;
import com.openbooking.inventory.api.dto.ReserveRoomResponse;
import com.openbooking.inventory.domain.model.RoomAvailability;
import com.openbooking.inventory.domain.repository.RoomAvailabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reservation strategy using distributed lock (Redis/Redisson) +
 * database atomic updates.
 *
 * This strategy guarantees:
 * - **ACID**: Availability changes are persisted atomically in the database
 * - **High Concurrency**: Uses a single SQL UPDATE per day with a guard condition
 * - **No Java-side race conditions**: Availability is never derived from in-memory
 *   entity state; instead, it is updated directly in the database.
 *
 * Technique:
 * - Use Redis distributed lock to coordinate across service instances (optional but
 *   useful for cross-region / cross-node coordination).
 * - Within the critical section, perform an atomic UPDATE:
 *
 *   UPDATE room_availability
 *   SET available_count = available_count - :quantity
 *   WHERE room_id = :roomId
 *     AND availability_date = :date
 *     AND available_count >= :quantity;
 *
 * - If the UPDATE affects 0 rows, there was not enough availability.
 */
@Slf4j
@Component("distributed")
@RequiredArgsConstructor
public class DistributedLockReservationStrategy implements ReservationStrategy {

    private final RoomAvailabilityRepository repository;
    private final RedissonClient redissonClient;

    @Override
    @Transactional
    public ReserveRoomResponse reserve(ReserveRoomRequest request) {
        String lockKey = buildLockKey(request.roomId(), request.checkInDate());
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // Try to acquire lock with timeout (wait max 5 seconds, hold for 30 seconds)
            boolean acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException("Unable to acquire lock for room reservation. Please try again.");
            }

            log.debug("Acquired distributed lock: {}", lockKey);
            return performReservationWithAtomicUpdate(request);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Reservation interrupted", e, "RESERVATION_INTERRUPTED");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Released distributed lock: {}", lockKey);
            }
        }
    }

    @Override
    public String getStrategyType() {
        return "DISTRIBUTED_LOCK";
    }

    /**
     * Performs reservation using **database atomic UPDATE** to avoid race conditions.
     *
     * Important:
     * - We do NOT load the entity and modify its state in Java.
     * - We call a single SQL UPDATE per date with a guard (available_count >= quantity).
     * - This ensures that even under very high concurrency, overselling cannot occur.
     */
    private ReserveRoomResponse performReservationWithAtomicUpdate(ReserveRoomRequest request) {
        List<LocalDate> dates = generateDateRange(request.checkInDate(), request.checkOutDate());

        // 1. Try to atomically decrease availability for each date
        for (LocalDate date : dates) {
            int updatedRows = repository.decreaseAvailabilityAtomically(
                    request.roomId(), date, request.quantity());

            if (updatedRows == 0) {
                // Either no row for this date, or not enough availability
                throw new BusinessException(
                        String.format(
                                "Insufficient availability for room %d on %s when reserving %d units",
                                request.roomId(), date, request.quantity()),
                        "INSUFFICIENT_AVAILABILITY");
            }
        }

        // 2. Calculate total price after successful atomic updates
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

    /**
     * Builds distributed lock key for room and date.
     */
    private String buildLockKey(Long roomId, LocalDate date) {
        return Constants.LOCK_PREFIX + roomId + ":" + date;
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
