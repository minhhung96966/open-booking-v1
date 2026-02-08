package com.openbooking.inventory.domain.repository;

import com.openbooking.inventory.domain.model.ReservationHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationHoldRepository extends JpaRepository<ReservationHold, Long> {

    List<ReservationHold> findByBookingId(Long bookingId);

    @Query("SELECT h FROM ReservationHold h WHERE h.expiresAt < :before ORDER BY h.expiresAt")
    List<ReservationHold> findExpiredBefore(@Param("before") LocalDateTime before);

    @Modifying
    @Query("DELETE FROM ReservationHold h WHERE h.bookingId = :bookingId")
    int deleteByBookingId(@Param("bookingId") Long bookingId);
}
