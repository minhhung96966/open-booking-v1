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
     * Confirm reservation (remove holds so expiry job does not release). Call after payment success.
     */
    @PostMapping("/reservations/confirm")
    public ResponseEntity<BaseResponse<Void>> confirmReservation(@RequestParam Long bookingId) {
        reservationService.confirmReservation(bookingId);
        return ResponseEntity.ok(BaseResponse.success(null));
    }

    /**
     * Release reserved rooms (used for Saga compensating transaction).
     * If bookingId is provided, also removes holds for that booking.
     */
    @PostMapping("/release")
    public ResponseEntity<BaseResponse<Void>> releaseRoom(
            @RequestParam Long roomId,
            @RequestParam String checkInDate,
            @RequestParam String checkOutDate,
            @RequestParam Integer quantity,
            @RequestParam(required = false) Long bookingId) {
        reservationService.releaseRoom(
                roomId,
                java.time.LocalDate.parse(checkInDate),
                java.time.LocalDate.parse(checkOutDate),
                quantity,
                bookingId);
        return ResponseEntity.ok(BaseResponse.success(null));
    }
}
