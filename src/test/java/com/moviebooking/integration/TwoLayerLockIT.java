package com.moviebooking.integration;

import com.moviebooking.common.AppException;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.dto.booking.HoldRequest;
import com.moviebooking.service.booking.BookingService;
import com.moviebooking.service.booking.internal.seathold.JdbcSeatHoldService;
import com.moviebooking.service.booking.internal.seathold.RedisSeatLockFilter;
import com.moviebooking.service.booking.internal.seathold.SeatHoldService;
import com.moviebooking.support.IntegrationTest;
import com.moviebooking.support.TestFixtures.Customer;
import com.moviebooking.support.TestFixtures.TestShow;
import com.moviebooking.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The Redis filter in front of the Postgres claim (HLD §8, LLD §15 "Redis layer (v2)").
 * Spies on the Postgres layer to prove losing clicks never reach it.
 */
class TwoLayerLockIT extends IntegrationTest {

    @MockitoSpyBean JdbcSeatHoldService postgresLayer;
    @Autowired BookingService bookingService;
    @Autowired SeatHoldService seatHold;
    @Autowired TestFixtures fx;

    @BeforeEach
    void resetSpy() {
        clearInvocations(postgresLayer);
    }

    @Test
    void fiveHundredUsersOneSeatOneWinnerAndPostgresBarelyTouched() throws Exception {
        TestShow show = fx.show(Duration.ofDays(4));
        Long seat = show.seat(0);
        List<Customer> customers = fx.customers(500);

        ExecutorService pool = Executors.newFixedThreadPool(500);
        CountDownLatch ready = new CountDownLatch(500);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ErrorCode>> results = new ArrayList<>();
        try {
            for (Customer c : customers) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        bookingService.hold(c.id(), new HoldRequest(show.showId(), List.of(seat), null));
                        return null;
                    } catch (AppException e) {
                        return e.code();
                    }
                }));
            }
            ready.await(20, TimeUnit.SECONDS);
            go.countDown();
            int winners = 0;
            int rejected = 0;
            for (Future<ErrorCode> f : results) {
                ErrorCode code = f.get(60, TimeUnit.SECONDS);
                if (code == null) {
                    winners++;
                } else if (code == ErrorCode.SEATS_UNAVAILABLE) {
                    rejected++;
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(rejected).isEqualTo(499);
        } finally {
            pool.shutdownNow();
        }
        assertThat(fx.count("SELECT count(*) FROM seat_lock WHERE show_id = ?", show.showId())).isEqualTo(1);
        // the Redis filter rejected the other 499 in memory
        verify(postgresLayer, atMost(3)).hold(eq(show.showId()), any(), any(), any());
    }

    @Test
    void ghostKeyBlocksUntilItsTtlRunsOutThenTheSeatIsHoldable() {
        TestShow show = fx.show(Duration.ofDays(4));
        Long seat = show.seat(1);
        // as if an app instance crashed after the Redis acquire, before the Postgres commit
        String key = RedisSeatLockFilter.key(show.showId(), seat);
        fx.redis().opsForValue().set(key, "999999", Duration.ofSeconds(30));

        Customer c = fx.customer();
        assertThatThrownBy(() -> bookingService.hold(c.id(), new HoldRequest(show.showId(), List.of(seat), null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).code()).isEqualTo(ErrorCode.SEATS_UNAVAILABLE);
        verify(postgresLayer, never()).hold(eq(show.showId()), any(), any(), any());
        assertThat(fx.redis().getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 30_000L);

        fx.redis().delete(key);                                          // the 30 s TTL ran out
        bookingService.hold(c.id(), new HoldRequest(show.showId(), List.of(seat), null));
    }

    @Test
    void redisKeyStartsShortLivedAndIsExtendedToTheLockDurationAfterCommit() {
        TestShow show = fx.show(Duration.ofDays(4));
        Long seat = show.seat(2);
        SeatHoldService.Reservation r = seatHold.reserve(show.showId(), List.of(seat), -1L);
        assertThat(fx.redis().getExpire(RedisSeatLockFilter.key(show.showId(), seat), TimeUnit.MILLISECONDS))
                .as("initial TTL before the Postgres commit").isBetween(1L, 30_000L);
        seatHold.abandon(r);
        assertThat(fx.redis().hasKey(RedisSeatLockFilter.key(show.showId(), seat))).isFalse();

        Customer c = fx.customer();
        Long bookingId = bookingService.hold(c.id(), new HoldRequest(show.showId(), List.of(seat), null)).bookingId();
        String key = RedisSeatLockFilter.key(show.showId(), seat);
        assertThat(fx.redis().opsForValue().get(key)).isEqualTo(bookingId.toString());
        assertThat(fx.redis().getExpire(key, TimeUnit.SECONDS)).as("10-min lock").isBetween(590L, 600L);

        bookingService.pay(c.id(), bookingId, UUID.randomUUID().toString(), null);
        assertThat(fx.redis().opsForValue().get(key)).isEqualTo("BOOKED");
        assertThat(fx.redis().getExpire(key, TimeUnit.HOURS)).as("marker lives until show end").isGreaterThan(90L);
    }

    @Test
    void bookedMarkerRejectsASoldSeatWithoutAPostgresCall() {
        TestShow show = fx.show(Duration.ofDays(4));
        Long seat = show.seat(3);
        Customer buyer = fx.customer();
        Long bookingId = bookingService.hold(buyer.id(), new HoldRequest(show.showId(), List.of(seat), null)).bookingId();
        bookingService.pay(buyer.id(), bookingId, UUID.randomUUID().toString(), null);
        clearInvocations(postgresLayer);

        assertThatThrownBy(() -> bookingService.hold(fx.customer().id(),
                new HoldRequest(show.showId(), List.of(seat), null)))
                .extracting(e -> ((AppException) e).code()).isEqualTo(ErrorCode.SEATS_UNAVAILABLE);
        verify(postgresLayer, never()).hold(any(), any(), any(), any());

        // cancelling frees the seat in both layers
        bookingService.cancel(buyer.id(), bookingId);
        assertThat(fx.redis().hasKey(RedisSeatLockFilter.key(show.showId(), seat))).isFalse();
        bookingService.hold(fx.customer().id(), new HoldRequest(show.showId(), List.of(seat), null));
    }

    @Test
    void staleRedisKeyNeverOverridesPostgres() {
        // Redis lost a key (failover): the request reaches Postgres, which still rejects the taken seat.
        TestShow show = fx.show(Duration.ofDays(4));
        Long seat = show.seat(4);
        bookingService.hold(fx.customer().id(), new HoldRequest(show.showId(), List.of(seat), null));
        fx.redis().delete(RedisSeatLockFilter.key(show.showId(), seat));

        assertThatThrownBy(() -> bookingService.hold(fx.customer().id(),
                new HoldRequest(show.showId(), List.of(seat), null)))
                .extracting(e -> ((AppException) e).code()).isEqualTo(ErrorCode.SEATS_UNAVAILABLE);
        assertThat(fx.count("SELECT count(*) FROM seat_lock WHERE show_id = ?", show.showId())).isEqualTo(1);
        // and the loser's own Redis key was cleaned up
        assertThat(fx.redis().hasKey(RedisSeatLockFilter.key(show.showId(), seat))).isFalse();
    }
}
