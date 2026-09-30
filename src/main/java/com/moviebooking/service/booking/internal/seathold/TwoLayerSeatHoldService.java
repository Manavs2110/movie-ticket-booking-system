package com.moviebooking.service.booking.internal.seathold;

import com.moviebooking.common.SeatsUnavailableException;
import com.moviebooking.config.AppProperties;
import com.moviebooking.service.booking.internal.seathold.JdbcSeatHoldService.Touched;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Redis filter (layer 1) in front of the Postgres claim (layer 2). HLD §8.2 rules:
 * <ul>
 *   <li>Redis keys start with a short TTL and are extended only after the Postgres commit, so a crash in
 *       between leaves a ghost key for at most {@code app.seat-lock.redis-initial-ttl}.</li>
 *   <li>Redis updates after a commit are best effort: a failure there never undoes committed state.</li>
 *   <li>If the layers disagree, Postgres wins.</li>
 * </ul>
 */
@Service
public class TwoLayerSeatHoldService implements SeatHoldService {

    private final RedisSeatLockFilter filter;
    private final JdbcSeatHoldService postgres;
    private final Duration initialTtl;

    public TwoLayerSeatHoldService(RedisSeatLockFilter filter, JdbcSeatHoldService postgres, AppProperties props) {
        this.filter = filter;
        this.postgres = postgres;
        this.initialTtl = props.seatLock().redisInitialTtl();
    }

    @Override
    public Reservation reserve(Long showId, List<Long> sortedSeatIds, Long bookingId) {
        RedisSeatLockFilter.Acquire acquire = filter.tryAcquire(showId, sortedSeatIds, bookingId, initialTtl);
        return new Reservation(showId, sortedSeatIds, bookingId, acquire.taken(), acquire.bypassed());
    }

    @Override
    public void abandon(Reservation r) {
        if (!r.bypassed() && !r.rejected()) {
            filter.release(r.showId(), r.seatIds(), r.bookingId(), false);
        }
    }

    @Override
    public List<Long> claim(Reservation r, Duration holdFor) {
        if (r.bypassed()) {
            // Without the Redis filter, reject obviously taken seats before taking any row lock.
            List<Long> taken = postgres.findTaken(r.showId(), r.seatIds());
            if (!taken.isEmpty()) {
                throw new SeatsUnavailableException(taken);
            }
        }
        List<Long> claimed = postgres.hold(r.showId(), r.bookingId(), r.seatIds(), holdFor);
        if (claimed.size() == r.seatIds().size()) {
            afterCommit(() -> filter.extend(r.showId(), r.seatIds(), r.bookingId(), holdFor));
        }
        return claimed;
    }

    @Override
    public Optional<Instant> extend(Long bookingId, int expectedSeats, Duration paymentWindow) {
        Touched t = postgres.extend(bookingId, paymentWindow);
        if (t.seatIds().size() != expectedSeats) {
            return Optional.empty();
        }
        afterCommit(() -> filter.extend(t.showId(), t.seatIds(), bookingId, Duration.between(t.dbNow(), t.heldUntil())));
        return Optional.of(t.heldUntil());
    }

    @Override
    public boolean confirm(Long bookingId, int expectedSeats, Instant bookedMarkerUntil) {
        Touched t = postgres.confirm(bookingId);
        if (t.seatIds().size() != expectedSeats) {
            return false;
        }
        afterCommit(() -> filter.markBooked(t.showId(), t.seatIds(), bookingId, bookedMarkerUntil));
        return true;
    }

    @Override
    public int release(Long bookingId) {
        Touched t = postgres.release(bookingId);
        if (!t.seatIds().isEmpty()) {
            afterCommit(() -> filter.release(t.showId(), t.seatIds(), bookingId, true));
        }
        return t.seatIds().size();
    }

    @Override
    public void releaseShow(Long showId, List<Long> allSeatIds) {
        filter.releaseShow(showId, allSeatIds);
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
