package com.openbooking.common.exception;

/**
 * Thrown when a required dependency (e.g. idempotency store) is temporarily unavailable.
 * Client should retry with the same idempotency key later.
 * Mapped to HTTP 503.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
