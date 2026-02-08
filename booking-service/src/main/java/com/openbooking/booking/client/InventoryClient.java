package com.openbooking.booking.client;

import com.openbooking.booking.client.dto.ReserveRoomRequest;
import com.openbooking.booking.client.dto.ReserveRoomResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Feign client for communicating with Inventory Service.
 * Used in Saga Orchestration for reserving/releasing rooms.
 * 
 * Note: Reservation strategy is configured in inventory-service application.yml
 */
@FeignClient(name = "inventory-service", path = "/api/v1/inventory")
public interface InventoryClient {

    @PostMapping("/reserve")
    ReserveRoomResponse reserveRoom(@RequestBody ReserveRoomRequest request);

    @PostMapping("/reservations/confirm")
    void confirmReservation(@org.springframework.web.bind.annotation.RequestParam Long bookingId);

    @PostMapping("/release")
    void releaseRoom(
            @org.springframework.web.bind.annotation.RequestParam Long roomId,
            @org.springframework.web.bind.annotation.RequestParam String checkInDate,
            @org.springframework.web.bind.annotation.RequestParam String checkOutDate,
            @org.springframework.web.bind.annotation.RequestParam Integer quantity,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long bookingId);
}
