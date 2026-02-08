package com.openbooking.booking.domain.repository;

import com.openbooking.booking.domain.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByUserId(Long userId);
    Optional<Booking> findByIdAndUserId(Long id, Long userId);

    /** For saga recovery: bookings stuck in RESERVE_SENT or PAYMENT_SENT before given time */
    List<Booking> findBySagaStepInAndUpdatedAtBefore(List<String> sagaSteps, LocalDateTime before);
}
