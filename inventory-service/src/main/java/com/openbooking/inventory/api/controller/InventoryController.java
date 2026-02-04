package com.openbooking.inventory.api.controller;

import com.openbooking.common.dto.BaseResponse;
import com.openbooking.inventory.api.dto.ReserveRoomRequest;
import com.openbooking.inventory.api.dto.ReserveRoomResponse;
import com.openbooking.inventory.domain.service.RoomReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for inventory operations.
 * Uses Strategy Pattern for reservation - strategy is selected via application.yml.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final RoomReservationService reservationService;

    /**
     * Reserve room using the configured reservation strategy.
     * Strategy is selected via application.yml: inventory.reservation.strategy
     * 
     * Available strategies:
     * - DISTRIBUTED_LOCK: Redis/Redisson distributed locks (recommended for production)
     * - PESSIMISTIC_LOCK: Database-level SELECT FOR UPDATE
     * - OPTIMISTIC_LOCK: Version-based optimistic locking with retry
     */
    @PostMapping("/reserve")
    public ResponseEntity<BaseResponse<ReserveRoomResponse>> reserveRoom(
            @Valid @RequestBody ReserveRoomRequest request) {
        ReserveRoomResponse response = reservationService.reserveRoom(request);
        return ResponseEntity.ok(BaseResponse.success("Room reserved successfully", response));
    }

    /**
     * Release reserved rooms (used for Saga compensating transaction).
     */
    @PostMapping("/release")
    public ResponseEntity<BaseResponse<Void>> releaseRoom(
            @RequestParam Long roomId,
            @RequestParam String checkInDate,
            @RequestParam String checkOutDate,
            @RequestParam Integer quantity) {
        reservationService.releaseRoom(
                roomId,
                java.time.LocalDate.parse(checkInDate),
                java.time.LocalDate.parse(checkOutDate),
                quantity);
        return ResponseEntity.ok(BaseResponse.success(null));
    }
}
