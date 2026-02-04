package com.openbooking.booking.saga;

import com.openbooking.booking.client.InventoryClient;
import com.openbooking.booking.client.PaymentClient;
import com.openbooking.booking.client.dto.ProcessPaymentRequest;
import com.openbooking.booking.client.dto.ProcessPaymentResponse;
import com.openbooking.booking.client.dto.ReserveRoomRequest;
import com.openbooking.booking.client.dto.ReserveRoomResponse;
import com.openbooking.booking.domain.model.Booking;
import com.openbooking.booking.domain.repository.BookingRepository;
import com.openbooking.booking.events.BookingEventPublisher;
import com.openbooking.common.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga Orchestrator for booking flow.
 * 
 * Implements Orchestration pattern where Booking Service coordinates the distributed transaction:
 * 
 * Flow:
 * 1. Reserve room (Inventory Service) - Step 1
 * 2. Process payment (Payment Service) - Step 2
 * 3. Confirm booking - Step 3
 * 
 * If any step fails, compensating transactions are executed:
 * - If payment fails: Release room (compensating transaction)
 * 
 * This is a manual implementation of Saga Pattern using Feign clients.
 * Alternative implementations:
 * - Kafka events (Choreography pattern) - see BookingEventPublisher
 * - Camunda workflow engine - for complex state machines
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingOrchestrator {

    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final BookingRepository bookingRepository;
    private final BookingEventPublisher bookingEventPublisher;

    /**
     * Orchestrates the booking saga with compensating transactions.
     * 
     * @param booking Booking entity to process
     * @return Updated booking with status
     */
    @Transactional
    @CircuitBreaker(name = "booking-orchestrator", fallbackMethod = "handleBookingFailure")
    public Booking orchestrateBooking(Booking booking) {
        log.info("Starting booking orchestration for booking ID: {}", booking.getId());

        try {
            // Step 1: Reserve room in Inventory Service
            log.info("Step 1: Reserving room {} for dates {}-{}", 
                    booking.getRoomId(), booking.getCheckInDate(), booking.getCheckOutDate());
            
            ReserveRoomRequest reserveRequest = new ReserveRoomRequest(
                    booking.getRoomId(),
                    booking.getCheckInDate(),
                    booking.getCheckOutDate(),
                    booking.getQuantity()
            );
            
            ReserveRoomResponse reserveResponse = reserveRoomWithRetry(reserveRequest);
            booking.setTotalPrice(reserveResponse.totalPrice());
            booking.setStatus(Booking.BookingStatus.PENDING);
            booking = bookingRepository.save(booking);

            // Step 2: Process payment in Payment Service
            log.info("Step 2: Processing payment for booking ID: {}, amount: {}", 
                    booking.getId(), booking.getTotalPrice());
            
            ProcessPaymentRequest paymentRequest = new ProcessPaymentRequest(
                    booking.getUserId(),
                    booking.getId(),
                    booking.getTotalPrice(),
                    "CREDIT_CARD" // Default payment method
            );
            
            ProcessPaymentResponse paymentResponse = processPaymentWithRetry(paymentRequest);
            
            if (!"SUCCESS".equals(paymentResponse.status())) {
                // Payment failed - execute compensating transaction
                log.error("Payment failed for booking ID: {}. Releasing room.", booking.getId());
                releaseRoom(booking);
                booking.setStatus(Booking.BookingStatus.FAILED);
                booking = bookingRepository.save(booking);
                throw new BusinessException("Payment processing failed: " + paymentResponse.message());
            }

            // Step 3: Confirm booking
            booking.setPaymentId(paymentResponse.paymentId());
            booking.setStatus(Booking.BookingStatus.CONFIRMED);
            booking = bookingRepository.save(booking);
            
            // Publish event for async processing (Choreography pattern)
            bookingEventPublisher.publishBookingConfirmed(booking);
            
            log.info("Booking orchestration completed successfully for booking ID: {}", booking.getId());
            return booking;

        } catch (Exception e) {
            log.error("Error during booking orchestration for booking ID: {}", booking.getId(), e);
            
            // Ensure room is released on any failure
            try {
                releaseRoom(booking);
            } catch (Exception releaseException) {
                log.error("Error releasing room for booking ID: {}", booking.getId(), releaseException);
            }
            
            booking.setStatus(Booking.BookingStatus.FAILED);
            booking = bookingRepository.save(booking);
            throw new BusinessException("Booking failed: " + e.getMessage(), e, "BOOKING_FAILED");
        }
    }

    /**
     * Reserves room with retry mechanism using Resilience4j.
     */
    @Retry(name = "inventory-service")
    private ReserveRoomResponse reserveRoomWithRetry(ReserveRoomRequest request) {
        return inventoryClient.reserveRoom(request);
    }

    /**
     * Processes payment with retry mechanism using Resilience4j.
     */
    @Retry(name = "payment-service")
    private ProcessPaymentResponse processPaymentWithRetry(ProcessPaymentRequest request) {
        return paymentClient.processPayment(request);
    }

    /**
     * Compensating transaction: Releases reserved room.
     */
    private void releaseRoom(Booking booking) {
        try {
            log.info("Releasing room {} for booking ID: {}", booking.getRoomId(), booking.getId());
            inventoryClient.releaseRoom(
                    booking.getRoomId(),
                    booking.getCheckInDate().toString(),
                    booking.getCheckOutDate().toString(),
                    booking.getQuantity()
            );
            log.info("Room released successfully for booking ID: {}", booking.getId());
        } catch (Exception e) {
            log.error("Error releasing room for booking ID: {}", booking.getId(), e);
            throw new BusinessException("Failed to release room", e, "ROOM_RELEASE_FAILED");
        }
    }

    /**
     * Fallback method for circuit breaker.
     */
    private Booking handleBookingFailure(Booking booking, Exception ex) {
        log.error("Circuit breaker opened for booking ID: {}", booking.getId(), ex);
        booking.setStatus(Booking.BookingStatus.FAILED);
        booking = bookingRepository.save(booking);
        
        // Try to release room
        try {
            releaseRoom(booking);
        } catch (Exception releaseException) {
            log.error("Error releasing room in fallback", releaseException);
        }
        
        return booking;
    }
}
