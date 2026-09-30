package com.moviebooking.model.booking;

import java.time.Instant;

public enum BookingStatus {
    HELD,
    PAYMENT_PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED;

    /**
     * The status every response reports. A HELD booking whose hold time has passed is EXPIRED,
     * even before anything has saved it as such (there is no cleanup job, HLD §5.4).
     */
    public static BookingStatus effective(BookingStatus stored, Instant holdExpiresAt, Instant dbNow) {
        if (stored == HELD && !holdExpiresAt.isAfter(dbNow)) {
            return EXPIRED;
        }
        return stored;
    }
}
