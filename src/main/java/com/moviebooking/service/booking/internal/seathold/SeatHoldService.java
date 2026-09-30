package com.moviebooking.service.booking.internal.seathold;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Seat locking as the booking code sees it (HLD §8). The implementation has two layers, a Redis filter
 * and the Postgres claim, but callers only follow this protocol:
 * <pre>
 *   reserve()           no transaction        taken non-empty → 409, Postgres never touched
 *   claim()             inside the booking TX  Postgres decides; fewer claimed → caller rolls back
 *   abandon()           after a failed TX      best effort
 *   extend/confirm/release   inside a TX       Redis is updated after commit, best effort
 * </pre>
 */
public interface SeatHoldService {

    /** Result of layer 1. {@code bypassed} = Redis unavailable, Postgres must do the cheap check itself. */
    record Reservation(Long showId, List<Long> seatIds, Long bookingId, List<Long> taken, boolean bypassed) {
        public boolean rejected() {
            return !taken.isEmpty();
        }
    }

    Reservation reserve(Long showId, List<Long> sortedSeatIds, Long bookingId);

    /** The booking transaction failed: drop the Redis keys this reservation set. */
    void abandon(Reservation reservation);

    /** @return the seat ids actually claimed in Postgres (fewer than requested → caller must roll back) */
    List<Long> claim(Reservation reservation, Duration holdFor);

    /**
     * Pushes the lock out to at least {@code paymentWindow} from now, only if this booking still validly holds
     * all {@code expectedSeats}. @return the new lock end, or empty if the hold was lost
     */
    Optional<Instant> extend(Long bookingId, int expectedSeats, Duration paymentWindow);

    /** Marks the booking's valid locks as sold. @return false if any seat was lost (caller rolls back) */
    boolean confirm(Long bookingId, int expectedSeats, Instant bookedMarkerUntil);

    /** Frees every seat this booking still owns. */
    int release(Long bookingId);

    /** Admin cancelled the show: forget all of its Redis keys. */
    void releaseShow(Long showId, List<Long> allSeatIds);
}
