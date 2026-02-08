package com.openbooking.booking.exception;

import com.openbooking.booking.domain.model.Booking;
import lombok.Getter;

/**
 * Thrown when the booking saga cannot complete within the request (503, timeout, etc.)
 * but we have not failed for sure — recovery will retry later.
 * API should return 202 Accepted with this booking so the client does not show "Failed";
 * client can poll GET /bookings/{id} or wait for notification.
 */
@Getter
public class BookingPendingUnclearException extends RuntimeException {

    private final Booking booking;

    public BookingPendingUnclearException(Booking booking, String message, Throwable cause) {
        super(message, cause);
        this.booking = booking;
    }
}
