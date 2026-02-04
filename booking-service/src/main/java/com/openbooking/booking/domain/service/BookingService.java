package com.openbooking.booking.domain.service;

import com.openbooking.booking.api.dto.BookingResponse;
import com.openbooking.booking.api.dto.CreateBookingRequest;
import com.openbooking.booking.domain.model.Booking;
import com.openbooking.booking.domain.repository.BookingRepository;
import com.openbooking.booking.saga.BookingOrchestrator;
import com.openbooking.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing bookings.
 * Delegates orchestration to BookingOrchestrator for Saga pattern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingOrchestrator orchestrator;

    /**
     * Creates a new booking and orchestrates the saga.
     */
    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request) {
        log.info("Creating booking for user ID: {}, room ID: {}", request.userId(), request.roomId());

        Booking booking = Booking.builder()
                .userId(request.userId())
                .roomId(request.roomId())
                .checkInDate(request.checkInDate())
                .checkOutDate(request.checkOutDate())
                .quantity(request.quantity())
                .status(Booking.BookingStatus.PENDING)
                .build();

        booking = bookingRepository.save(booking);

        // Orchestrate saga: Reserve room -> Process payment -> Confirm
        booking = orchestrator.orchestrateBooking(booking);

        return BookingResponse.from(booking);
    }

    /**
     * Retrieves booking by ID.
     */
    public BookingResponse getBookingById(Long id) {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", id));
        return BookingResponse.from(booking);
    }

    /**
     * Retrieves all bookings for a user.
     */
    public List<BookingResponse> getBookingsByUserId(Long userId) {
        return bookingRepository.findByUserId(userId).stream()
                .map(BookingResponse::from)
                .collect(Collectors.toList());
    }
}
