package com.moviebooking.integration;

import com.moviebooking.common.AppException;
import com.moviebooking.dto.booking.BookingResponse;
import com.moviebooking.dto.booking.HoldRequest;
import com.moviebooking.service.booking.BookingService;
import com.moviebooking.support.IntegrationTest;
import com.moviebooking.support.TestFixtures.Customer;
import com.moviebooking.support.TestFixtures.TestShow;
import com.moviebooking.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contention tests on real Postgres (LLD §15): many threads released at the same instant by a latch.
 * Each outcome must be either success or a clean business error, never a deadlock or a partial hold.
 */
class ConcurrencyIT extends IntegrationTest {

    private static final int THREADS = 50;

    @Autowired BookingService bookingService;
    @Autowired TestFixtures fx;

    private enum Outcome { OK, SEATS_UNAVAILABLE, ACTIVE_HOLD_EXISTS, INVALID_DISCOUNT, OTHER }

    @Test
    void fiftyUsersOneSeatExactlyOneWinner() throws Exception {
        TestShow show = fx.show(Duration.ofDays(4));
        Long seat = show.seat(0);
        List<Customer> customers = IntStream.range(0, THREADS).mapToObj(i -> fx.customer()).toList();

        List<Outcome> outcomes = race(customers.stream().<Callable<Outcome>>map(c -> () ->
                outcome(() -> bookingService.hold(c.id(), new HoldRequest(show.showId(), List.of(seat), null)))).toList());

        assertThat(outcomes).filteredOn(o -> o == Outcome.OK).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o == Outcome.SEATS_UNAVAILABLE).hasSize(THREADS - 1);
        assertThat(fx.count("SELECT count(*) FROM seat_lock WHERE show_id = ? AND seat_id = ?", show.showId(), seat))
                .isEqualTo(1);
        assertThat(fx.count("SELECT count(*) FROM booking WHERE show_id = ?", show.showId())).isEqualTo(1);
    }

    @Test
    void overlappingMultiSeatHoldsNeverDeadlockOrPartiallyHold() throws Exception {
        TestShow show = fx.show(Duration.ofDays(4));
        // Pairs of requests that overlap on one seat, in both directions: A1-A3 vs A3-A5, A5-A7 vs A7-A9 ...
        List<Callable<Outcome>> tasks = new ArrayList<>();
        List<List<Long>> requested = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            int base = (round % 4) * 8;
            List<Long> left = List.of(show.seat(base), show.seat(base + 1), show.seat(base + 2));
            List<Long> right = List.of(show.seat(base + 4), show.seat(base + 3), show.seat(base + 2)); // reversed order
            for (List<Long> seats : List.of(left, right)) {
                Customer c = fx.customer();
                requested.add(seats);
                tasks.add(() -> outcome(() -> bookingService.hold(c.id(), new HoldRequest(show.showId(), seats, null))));
            }
        }
        List<Outcome> outcomes = race(tasks);

        assertThat(outcomes).doesNotContain(Outcome.OTHER);          // no deadlock / unexpected error
        // all or nothing: every booking owns exactly the seats it asked for
        List<Long> bookingIds = fx.jdbc().queryForList("SELECT id FROM booking WHERE show_id = ?", Long.class, show.showId());
        for (Long id : bookingIds) {
            int items = fx.count("SELECT count(*) FROM booking_item WHERE booking_id = ?", id);
            int locks = fx.count("SELECT count(*) FROM seat_lock WHERE booking_id = ?", id);
            assertThat(locks).isEqualTo(items).isEqualTo(3);
        }
        assertThat(fx.count("SELECT count(*) FROM seat_lock WHERE show_id = ?", show.showId()))
                .isEqualTo(bookingIds.size() * 3);
    }

    @Test
    void discountWithUsageLimitOnePaidByTenUsersAtOnceIsRedeemedOnce() throws Exception {
        TestShow show = fx.show(Duration.ofDays(4));
        String code = "RACE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Long codeId = fx.discountCode(code, "FLAT", "20", 1, 1);
        List<Callable<Outcome>> payments = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Customer c = fx.customer();
            BookingResponse held = bookingService.hold(c.id(), new HoldRequest(show.showId(), List.of(show.seat(i)), code));
            payments.add(() -> outcome(() -> bookingService.pay(c.id(), held.bookingId(), UUID.randomUUID().toString(), null)));
        }
        List<Outcome> outcomes = race(payments);

        assertThat(outcomes).filteredOn(o -> o == Outcome.OK).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o == Outcome.INVALID_DISCOUNT).hasSize(9);
        assertThat(fx.count("SELECT used_count FROM discount_code WHERE id = ?", codeId)).isEqualTo(1);
        assertThat(fx.count("SELECT count(*) FROM booking WHERE discount_code_id = ? AND discount_redeemed", codeId)).isEqualTo(1);
    }

    @Test
    void sameUserDoubleClickingHoldCreatesOneBooking() throws Exception {
        TestShow show = fx.show(Duration.ofDays(4));
        Customer c = fx.customer();
        List<Callable<Outcome>> clicks = IntStream.range(0, 10).<Callable<Outcome>>mapToObj(i -> () ->
                outcome(() -> bookingService.hold(c.id(), new HoldRequest(show.showId(), List.of(show.seat(i)), null))))
                .toList();
        List<Outcome> outcomes = race(clicks);

        assertThat(outcomes).filteredOn(o -> o == Outcome.OK).hasSize(1);
        assertThat(outcomes).doesNotContain(Outcome.OTHER);
        assertThat(fx.count("SELECT count(*) FROM booking WHERE user_id = ? AND show_id = ?", c.id(), show.showId()))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private static Outcome outcome(Runnable call) {
        try {
            call.run();
            return Outcome.OK;
        } catch (AppException e) {
            return switch (e.code()) {
                case SEATS_UNAVAILABLE -> Outcome.SEATS_UNAVAILABLE;
                case ACTIVE_HOLD_EXISTS -> Outcome.ACTIVE_HOLD_EXISTS;
                case INVALID_DISCOUNT -> Outcome.INVALID_DISCOUNT;
                default -> Outcome.OTHER;
            };
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // the partial unique index caught a race the pre-check missed; the API maps it to 409
            String constraint = String.valueOf(e.getMostSpecificCause().getMessage());
            if (constraint.contains("ux_booking_active_hold")) {
                return Outcome.ACTIVE_HOLD_EXISTS;
            }
            if (constraint.contains("seat_lock_pkey")) {
                return Outcome.SEATS_UNAVAILABLE;
            }
            return Outcome.OTHER;
        } catch (RuntimeException e) {
            e.printStackTrace();
            return Outcome.OTHER;
        }
    }

    private static List<Outcome> race(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
