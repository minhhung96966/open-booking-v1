package com.openbooking.inventory.domain.strategy;

import com.openbooking.inventory.api.dto.ReserveRoomRequest;
import com.openbooking.inventory.api.dto.ReserveRoomResponse;

/**
 * Strategy interface for room reservation with different concurrency control mechanisms.
 * 
 * Implementations:
 * - DistributedLockReservationStrategy: Uses Redis/Redisson distributed locks
 * - PessimisticLockReservationStrategy: Uses database-level SELECT FOR UPDATE
 * - OptimisticLockReservationStrategy: Uses version-based optimistic locking with retry
 */
public interface ReservationStrategy {
    
    /**
     * Reserves a room using the specific locking strategy.
     * 
     * @param request Reservation request
     * @return Reservation response
     */
    ReserveRoomResponse reserve(ReserveRoomRequest request);
    
    /**
     * Returns the strategy type name for identification.
     * 
     * @return Strategy type (DISTRIBUTED_LOCK, PESSIMISTIC_LOCK, OPTIMISTIC_LOCK)
     */
    String getStrategyType();
}
