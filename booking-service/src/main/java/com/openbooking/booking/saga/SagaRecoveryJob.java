package com.openbooking.booking.saga;

import com.openbooking.booking.domain.model.Booking;
import com.openbooking.booking.domain.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled job to advance bookings stuck in RESERVE_SENT or PAYMENT_SENT.
 * Retries with same idempotency key so duplicate calls are safe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaRecoveryJob {

    private final BookingRepository bookingRepository;
    private final BookingOrchestrator orchestrator;

    @Value("${booking.saga.recovery-enabled:true}")
    private boolean recoveryEnabled;

    @Value("${booking.saga.recovery-threshold-minutes:10}")
    private int recoveryThresholdMinutes;

    /** Stuck longer than this: give up (FAILED + release). No more retries. */
    @Value("${booking.saga.recovery-give-up-minutes:1440}")
    private int recoveryGiveUpMinutes;

    @Scheduled(fixedDelayString = "${booking.saga.recovery-interval-ms:300000}")
    @Transactional(readOnly = true)
    public void recoverStuckSagas() {
        if (!recoveryEnabled) return;
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(recoveryThresholdMinutes);
        LocalDateTime giveUpThreshold = LocalDateTime.now().minusMinutes(recoveryGiveUpMinutes);
        List<Booking> stuck = bookingRepository.findBySagaStepInAndUpdatedAtBefore(
                List.of("RESERVE_SENT", "PAYMENT_SENT"), threshold);
        if (stuck.isEmpty()) return;
        log.info("Saga recovery: found {} stuck booking(s)", stuck.size());
        for (Booking b : stuck) {
            try {
                if (b.getUpdatedAt() != null && b.getUpdatedAt().isBefore(giveUpThreshold)) {
                    log.warn("Recovery giving up on booking {} (stuck > {} min), step={}", b.getId(), recoveryGiveUpMinutes, b.getSagaStep());
                    if ("PAYMENT_SENT".equals(b.getSagaStep())) {
                        log.warn("Booking {} stuck at PAYMENT_SENT: room not released; manual reconciliation required (check payment then confirm or refund)", b.getId());
                    }
                    orchestrator.giveUpStuckBooking(b);
                } else {
                    orchestrator.advanceStuckBooking(b);
                }
            } catch (Exception e) {
                log.error("Recovery failed for booking {}", b.getId(), e);
            }
        }
    }
}
