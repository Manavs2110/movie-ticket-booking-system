package com.moviebooking.service.catalog;

import java.util.Collection;

/**
 * Lets the catalog ask "is this show / screen in use?" without depending on the booking module.
 * Implemented by the booking module (dependency inversion keeps catalog → booking free of cycles).
 */
public interface ShowUsageChecker {

    /** Any booking at all (any status) exists for the show. */
    boolean hasBookings(Long showId);

    /** Any seat of these shows is currently held or sold. */
    boolean hasSeatActivity(Collection<Long> showIds);
}
