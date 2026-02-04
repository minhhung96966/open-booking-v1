package com.openbooking.booking.api.controller;

import com.openbooking.booking.api.dto.BookingResponse;
import com.openbooking.booking.api.dto.CreateBookingRequest;
import com.openbooking.booking.domain.service.BookingService;
import com.openbooking.common.dto.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for booking operations.
 * Demonstrates Saga Pattern orchestration.
 */
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public ResponseEntity<BaseResponse<BookingResponse>> createBooking(
            @Valid @RequestBody CreateBookingRequest request) {
        BookingResponse response = bookingService.createBooking(request);
        return ResponseEntity.ok(BaseResponse.success("Booking created successfully", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BaseResponse<BookingResponse>> getBooking(@PathVariable Long id) {
        BookingResponse response = bookingService.getBookingById(id);
        return ResponseEntity.ok(BaseResponse.success(response));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<BaseResponse<List<BookingResponse>>> getBookingsByUser(
            @PathVariable Long userId) {
        List<BookingResponse> response = bookingService.getBookingsByUserId(userId);
        return ResponseEntity.ok(BaseResponse.success(response));
    }
}
