package com.openbooking.inventory.domain.repository;

import com.openbooking.inventory.domain.model.RoomAvailability;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for RoomAvailability entity.
 * Provides methods for both optimistic and pessimistic locking.
 */
public interface RoomAvailabilityRepository extends JpaRepository<RoomAvailability, Long> {

    /**
     * Find room availability with pessimistic lock (SELECT FOR UPDATE).
     * Use this method when you need to guarantee exclusive access in concurrent scenarios.
     * Note: This can cause deadlocks if not used carefully.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RoomAvailability r WHERE r.roomId = :roomId AND r.availabilityDate = :date")
    Optional<RoomAvailability> findByRoomIdAndDateWithLock(
            @Param("roomId") Long roomId,
            @Param("date") LocalDate date);

    /**
     * Find room availability without lock (for read operations).
     */
    Optional<RoomAvailability> findByRoomIdAndAvailabilityDate(Long roomId, LocalDate date);

    /**
     * Find all availabilities for a room within date range.
     */
    List<RoomAvailability> findByRoomIdAndAvailabilityDateBetween(
            Long roomId, LocalDate startDate, LocalDate endDate);

    /**
     * Atomically decreases availableCount for a specific room and date.
     *
     * This uses a single SQL UPDATE statement with a guard condition
     * (available_count >= :quantity) to guarantee:
     * - No overselling (race conditions are prevented at the database level)
     * - High concurrency with minimal locking window
     *
     * Returns the number of rows affected:
     * - 1: success, availability was decreased
     * - 0: failure, not enough availability
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE RoomAvailability r
           SET r.availableCount = r.availableCount - :quantity
           WHERE r.roomId = :roomId
             AND r.availabilityDate = :date
             AND r.availableCount >= :quantity
           """)
    int decreaseAvailabilityAtomically(@Param("roomId") Long roomId,
                                       @Param("date") LocalDate date,
                                       @Param("quantity") int quantity);
}
