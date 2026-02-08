package com.openbooking.booking.api.exception;

import com.openbooking.booking.api.dto.BookingResponse;
import com.openbooking.booking.exception.BookingPendingUnclearException;
import com.openbooking.common.dto.BaseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * When saga cannot complete in request (503/timeout) but recovery will retry:
 * return 202 Accepted with booking so client shows "processing" not "failed".
 */
@Slf4j
@RestControllerAdvice
public class BookingExceptionHandler {

    @ExceptionHandler(BookingPendingUnclearException.class)
    public ResponseEntity<BaseResponse<BookingResponse>> handleBookingPendingUnclear(
            BookingPendingUnclearException ex) {
        log.warn("Booking {} pending (unclear outcome): {}", ex.getBooking().getId(), ex.getMessage());
        BookingResponse body = BookingResponse.from(ex.getBooking());
        BaseResponse<BookingResponse> response = BaseResponse.success(
                "Booking is being processed. Check status shortly or we will notify you when confirmed or failed.",
                body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
