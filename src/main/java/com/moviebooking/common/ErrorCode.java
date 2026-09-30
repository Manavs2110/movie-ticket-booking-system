package com.moviebooking.common;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes (LLD §13.3). */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    INVALID_SEATS(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    PAYMENT_FAILED(HttpStatus.PAYMENT_REQUIRED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND),
    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND),
    SEATS_UNAVAILABLE(HttpStatus.CONFLICT),
    ACTIVE_HOLD_EXISTS(HttpStatus.CONFLICT),
    HOLD_EXPIRED(HttpStatus.CONFLICT),
    INVALID_BOOKING_STATE(HttpStatus.CONFLICT),
    PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    SHOW_NOT_BOOKABLE(HttpStatus.CONFLICT),
    SHOW_OVERLAP(HttpStatus.CONFLICT),
    LAYOUT_IN_USE(HttpStatus.CONFLICT),
    EMAIL_TAKEN(HttpStatus.CONFLICT),
    CONFLICT(HttpStatus.CONFLICT),
    INVALID_DISCOUNT(HttpStatus.UNPROCESSABLE_ENTITY),
    CANCELLATION_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
