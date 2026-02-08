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
import com.openbooking.booking.exception.BookingPendingUnclearException;
import com.openbooking.common.exception.BusinessException;
import com.openbooking.common.exception.ServiceUnavailableException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

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
            String idempotencyKey = "booking-" + booking.getId();

            // Step 1: Reserve room in Inventory Service
            log.info("Step 1: Reserving room {} for dates {}-{}", 
                    booking.getRoomId(), booking.getCheckInDate(), booking.getCheckOutDate());
            booking.setSagaStep("RESERVE_SENT");
            booking = bookingRepository.save(booking);

            ReserveRoomRequest reserveRequest = new ReserveRoomRequest(
                    booking.getRoomId(),
                    booking.getCheckInDate(),
                    booking.getCheckOutDate(),
                    booking.getQuantity(),
                    idempotencyKey
            );
            ReserveRoomResponse reserveResponse = reserveRoomWithRetry(reserveRequest);
            booking.setTotalPrice(reserveResponse.totalPrice());
            booking.setStatus(Booking.BookingStatus.PENDING);
            booking.setSagaStep("RESERVE_OK");
            booking = bookingRepository.save(booking);

            // Step 2: Process payment in Payment Service
            log.info("Step 2: Processing payment for booking ID: {}, amount: {}", 
                    booking.getId(), booking.getTotalPrice());
            booking.setSagaStep("PAYMENT_SENT");
            booking = bookingRepository.save(booking);

            ProcessPaymentRequest paymentRequest = new ProcessPaymentRequest(
                    booking.getUserId(),
                    booking.getId(),
                    booking.getTotalPrice(),
                    "CREDIT_CARD",
                    idempotencyKey
            );
            
            ProcessPaymentResponse paymentResponse = processPaymentWithRetry(paymentRequest);
            
            if (!"SUCCESS".equals(paymentResponse.status())) {
                log.error("Payment failed for booking ID: {}. Releasing room.", booking.getId());
                releaseRoom(booking);
                booking.setStatus(Booking.BookingStatus.FAILED);
                booking.setSagaStep("FAILED");
                booking = bookingRepository.save(booking);
                throw new BusinessException("Payment processing failed: " + paymentResponse.message());
            }

            // Step 3: Confirm booking and remove reservation holds (so TTL job does not release)
            inventoryClient.confirmReservation(booking.getId());
            booking.setPaymentId(paymentResponse.paymentId());
            booking.setStatus(Booking.BookingStatus.CONFIRMED);
            booking.setSagaStep("CONFIRMED");
            booking = bookingRepository.save(booking);

            // Publish event for async processing (Choreography pattern)
            bookingEventPublisher.publishBookingConfirmed(booking);
            
            log.info("Booking orchestration completed successfully for booking ID: {}", booking.getId());
            return booking;

        } catch (Exception e) {
            log.error("Error during booking orchestration for booking ID: {}", booking.getId(), e);

            String step = booking.getSagaStep();
            if (("RESERVE_SENT".equals(step) || "PAYMENT_SENT".equals(step)) && isUnclearFailure(e)) {
                // Do not release; do not mark FAILED. Return 202 so client shows "processing" not "failed".
                // Recovery will retry; when it completes we notify. API and eventual state stay consistent.
                log.warn("Unclear failure (503/timeout) for booking {} at step {} - returning 202, recovery will retry", booking.getId(), step);
                booking = bookingRepository.save(booking);
                throw new BookingPendingUnclearException(booking,
                        "Booking is being processed. Check status shortly.", e);
            }

            try {
                releaseRoom(booking);
            } catch (Exception releaseException) {
                log.error("Error releasing room for booking ID: {}", booking.getId(), releaseException);
            }

            booking.setStatus(Booking.BookingStatus.FAILED);
            booking.setSagaStep("FAILED");
            booking = bookingRepository.save(booking);
            throw new BusinessException("Booking failed: " + e.getMessage(), e, "BOOKING_FAILED");
        }
    }

    /** True if we cannot be sure whether the remote call succeeded (503, timeout, etc.). */
    private boolean isUnclearFailure(Throwable e) {
        if (e == null) return false;
        if (e instanceof ServiceUnavailableException) return true;
        if (e instanceof FeignException fe && (fe.status() == 503 || fe.status() == 504)) return true;
        if (e instanceof TimeoutException || e instanceof SocketTimeoutException) return true;
        Throwable cause = e.getCause();
        if (cause != null && cause != e) return isUnclearFailure(cause);
        return false;
    }

    /**
     * Called by recovery job when a booking has been stuck longer than give-up threshold.
     * - RESERVE_SENT: we never got reserve OK → safe to release room and set FAILED.
     * - PAYMENT_SENT: we don't know if payment succeeded (money may have been taken).
     *   Do NOT release room here; only set FAILED. Requires manual reconciliation (check
     *   payment service, then confirm or refund). Releasing would risk "user charged, no room".
     */
    @Transactional
    public void giveUpStuckBooking(Booking booking) {
        Booking b = bookingRepository.findById(booking.getId()).orElse(null);
        if (b == null) return;
        booking = b;
        String step = booking.getSagaStep();
        if (!"RESERVE_SENT".equals(step) && !"PAYMENT_SENT".equals(step)) {
            return;
        }
        if ("RESERVE_SENT".equals(step)) {
            try {
                releaseRoom(booking);
            } catch (Exception e) {
                log.error("Release failed when giving up booking {}", booking.getId(), e);
            }
        }
        // PAYMENT_SENT: do not release — payment might have succeeded; manual check needed.
        booking.setStatus(Booking.BookingStatus.FAILED);
        booking.setSagaStep("FAILED");
        bookingRepository.save(booking);
    }

    /**
     * Used by saga recovery job: advance a stuck booking (RESERVE_SENT or PAYMENT_SENT).
     * Retries with same idempotency key so duplicate calls are safe.
     * On "unclear" failure (503, timeout) does not give up; job will retry next run.
     */
    @Transactional
    public void advanceStuckBooking(Booking booking) {
        Booking b = bookingRepository.findById(booking.getId()).orElse(null);
        if (b == null) return;
        booking = b;
        String step = booking.getSagaStep();
        if (step == null || (!"RESERVE_SENT".equals(step) && !"PAYMENT_SENT".equals(step))) {
            return;
        }
        String idempotencyKey = "booking-" + booking.getId();
        try {
            if ("RESERVE_SENT".equals(step)) {
                ReserveRoomRequest req = new ReserveRoomRequest(
                        booking.getRoomId(), booking.getCheckInDate(), booking.getCheckOutDate(),
                        booking.getQuantity(), idempotencyKey);
                ReserveRoomResponse res = reserveRoomWithRetry(req);
                booking.setTotalPrice(res.totalPrice());
                booking.setSagaStep("RESERVE_OK");
                booking = bookingRepository.save(booking);
                // Continue to payment in same run
                step = "RESERVE_OK";
            }
            if ("RESERVE_OK".equals(step)) {
                booking.setSagaStep("PAYMENT_SENT");
                booking = bookingRepository.save(booking);
                ProcessPaymentRequest payReq = new ProcessPaymentRequest(
                        booking.getUserId(), booking.getId(), booking.getTotalPrice(),
                        "CREDIT_CARD", idempotencyKey);
                ProcessPaymentResponse payRes = processPaymentWithRetry(payReq);
                if (!"SUCCESS".equals(payRes.status())) {
                    releaseRoom(booking);
                    booking.setStatus(Booking.BookingStatus.FAILED);
                    booking.setSagaStep("FAILED");
                    bookingRepository.save(booking);
                    return;
                }
                inventoryClient.confirmReservation(booking.getId());
                booking.setPaymentId(payRes.paymentId());
                booking.setStatus(Booking.BookingStatus.CONFIRMED);
                booking.setSagaStep("CONFIRMED");
                bookingRepository.save(booking);
                bookingEventPublisher.publishBookingConfirmed(booking, true);
                log.info("Recovery: booking {} advanced to CONFIRMED", booking.getId());
            } else if ("PAYMENT_SENT".equals(step)) {
                ProcessPaymentRequest payReq = new ProcessPaymentRequest(
                        booking.getUserId(), booking.getId(), booking.getTotalPrice(),
                        "CREDIT_CARD", idempotencyKey);
                ProcessPaymentResponse payRes = processPaymentWithRetry(payReq);
                if (!"SUCCESS".equals(payRes.status())) {
                    releaseRoom(booking);
                    booking.setStatus(Booking.BookingStatus.FAILED);
                    booking.setSagaStep("FAILED");
                    bookingRepository.save(booking);
                    return;
                }
                inventoryClient.confirmReservation(booking.getId());
                booking.setPaymentId(payRes.paymentId());
                booking.setStatus(Booking.BookingStatus.CONFIRMED);
                booking.setSagaStep("CONFIRMED");
                bookingRepository.save(booking);
                bookingEventPublisher.publishBookingConfirmed(booking, true);
                log.info("Recovery: booking {} advanced to CONFIRMED", booking.getId());
            }
        } catch (Exception e) {
            log.warn("Recovery failed for booking {}: {}", booking.getId(), e.getMessage());
            if (isUnclearFailure(e)) {
                // Do not give up: leave in PAYMENT_SENT/RESERVE_SENT so next recovery run will retry.
                // Only give up after stuck longer than recovery-give-up-minutes (see SagaRecoveryJob).
                return;
            }
            try {
                releaseRoom(booking);
            } catch (Exception ex) {
                log.error("Release failed during recovery for booking {}", booking.getId(), ex);
            }
            booking.setStatus(Booking.BookingStatus.FAILED);
            booking.setSagaStep("FAILED");
            bookingRepository.save(booking);
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
                    booking.getQuantity(),
                    booking.getId()
            );
            log.info("Room released successfully for booking ID: {}", booking.getId());
        } catch (Exception e) {
            log.error("Error releasing room for booking ID: {}", booking.getId(), e);
            throw new BusinessException("Failed to release room", e, "ROOM_RELEASE_FAILED");
        }
    }

    /**
     * Fallback method for circuit breaker.
     * When circuit opens we may be at PAYMENT_SENT (payment might have succeeded); do not release.
     */
    private Booking handleBookingFailure(Booking booking, Exception ex) {
        log.error("Circuit breaker opened for booking ID: {}", booking.getId(), ex);
        if (!"PAYMENT_SENT".equals(booking.getSagaStep())) {
            try {
                releaseRoom(booking);
            } catch (Exception releaseException) {
                log.error("Error releasing room in fallback", releaseException);
            }
        }
        booking.setStatus(Booking.BookingStatus.FAILED);
        booking.setSagaStep("FAILED");
        booking = bookingRepository.save(booking);
        return booking;
    }
}
