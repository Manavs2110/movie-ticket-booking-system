package com.moviebooking.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.support.HookedPaymentGateway;
import com.moviebooking.support.IntegrationTest;
import com.moviebooking.support.TestFixtures.Customer;
import com.moviebooking.support.TestFixtures.TestShow;
import com.moviebooking.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hold → pay → confirm and every failure path of LLD §6, through the real HTTP API. */
class BookingFlowIT extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestFixtures fx;
    @Autowired HookedPaymentGateway gateway;

    @AfterEach
    void resetGateway() {
        gateway.reset();
    }

    // ---------------------------------------------------------------- happy path

    @Test
    void holdPayConfirm() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));

        JsonNode held = body(hold(c, show.showId(), List.of(show.seat(1), show.seat(35)), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.items.length()").value(2)));
        long bookingId = held.get("bookingId").asLong();
        assertThat(held.get("holdExpiresAt").isNull()).isFalse();
        assertThat(fx.count("SELECT count(*) FROM seat_lock WHERE booking_id = ? AND NOT booked", bookingId)).isEqualTo(2);

        pay(c, bookingId, key(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.holdExpiresAt").doesNotExist());

        assertThat(fx.count("SELECT count(*) FROM seat_lock WHERE booking_id = ? AND booked", bookingId)).isEqualTo(2);
        assertThat(fx.string("SELECT status FROM payment WHERE booking_id = ?", bookingId)).isEqualTo("SUCCESS");
        assertThat(fx.count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'BOOKING_CONFIRMED'",
                bookingId)).isEqualTo(1);
    }

    @Test
    void secondCustomerGets409WithTheExactUnavailableSeats() throws Exception {
        TestShow show = fx.show(Duration.ofDays(3));
        hold(fx.customer(), show.showId(), List.of(show.seat(0), show.seat(1)), null).andExpect(status().isCreated());

        hold(fx.customer(), show.showId(), List.of(show.seat(1), show.seat(2)), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.unavailableSeatIds", contains(show.seat(1).intValue())));
        // all or nothing: seat 2 was not taken by the failed request
        assertThat(fx.count("SELECT count(*) FROM seat_lock WHERE show_id = ? AND seat_id = ?", show.showId(), show.seat(2)))
                .isZero();
    }

    @Test
    void holdValidation() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        TestShow other = fx.show(Duration.ofDays(3));

        hold(c, show.showId(), List.of(show.seat(0), show.seat(0)), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SEATS"));
        hold(c, show.showId(), List.of(other.seat(0)), null)          // seat from another screen
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SEATS"));
        hold(c, show.showId(), show.seatIds().subList(0, 11), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        hold(c, 999_999L, List.of(show.seat(0)), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOW_NOT_FOUND"));
    }

    @Test
    void brokenSeatIsRejectedForNewHolds() throws Exception {
        TestShow show = fx.show(Duration.ofDays(3));
        fx.jdbc().update("UPDATE seat SET active = false WHERE id = ?", show.seat(5));
        hold(fx.customer(), show.showId(), List.of(show.seat(5)), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SEATS"));
    }

    @Test
    void oneActiveHoldPerCustomerPerShow() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        long first = body(hold(c, show.showId(), List.of(show.seat(0)), null)).get("bookingId").asLong();

        hold(c, show.showId(), List.of(show.seat(1)), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVE_HOLD_EXISTS"));

        // once the first hold has expired, a new one is allowed and the stale row is saved as EXPIRED
        fx.expireHold(first);
        hold(c, show.showId(), List.of(show.seat(1)), null).andExpect(status().isCreated());
        assertThat(fx.string("SELECT status FROM booking WHERE id = ?", first)).isEqualTo("EXPIRED");
    }

    // ---------------------------------------------------------------- expiry

    @Test
    void expiredHoldFreesTheSeatAndTheLatePaymentIsRejectedWithoutCharge() throws Exception {
        Customer slow = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        long bookingId = body(hold(slow, show.showId(), List.of(show.seat(3)), null)).get("bookingId").asLong();

        fx.expireHold(bookingId);
        mvc.perform(get("/api/bookings/{id}", bookingId).with(auth(slow)))
                .andExpect(jsonPath("$.status").value("EXPIRED"));            // derived on read, nothing saved yet

        hold(fx.customer(), show.showId(), List.of(show.seat(3)), null).andExpect(status().isCreated());

        int chargesBefore = gateway.successfulCharges();
        pay(slow, bookingId, key(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        assertThat(gateway.successfulCharges()).isEqualTo(chargesBefore);
        assertThat(fx.string("SELECT status FROM booking WHERE id = ?", bookingId)).isEqualTo("EXPIRED");
        assertThat(fx.count("SELECT count(*) FROM payment WHERE booking_id = ?", bookingId)).isZero();
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void replayingTheSameIdempotencyKeyChargesOnce() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        long bookingId = body(hold(c, show.showId(), List.of(show.seat(0)), null)).get("bookingId").asLong();
        String key = key();

        int before = gateway.successfulCharges();
        pay(c, bookingId, key, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        pay(c, bookingId, key, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(gateway.successfulCharges()).isEqualTo(before + 1);
        assertThat(fx.count("SELECT count(*) FROM payment WHERE booking_id = ?", bookingId)).isEqualTo(1);
    }

    @Test
    void reusingAKeyForAnotherBookingIsRejected() throws Exception {
        Customer c = fx.customer();
        TestShow a = fx.show(Duration.ofDays(3));
        TestShow b = fx.show(Duration.ofDays(3));
        long first = body(hold(c, a.showId(), List.of(a.seat(0)), null)).get("bookingId").asLong();
        long second = body(hold(c, b.showId(), List.of(b.seat(0)), null)).get("bookingId").asLong();
        String key = key();
        pay(c, first, key, null).andExpect(status().isOk());
        pay(c, second, key, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void payWithoutIdempotencyKeyIs400() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        long bookingId = body(hold(c, show.showId(), List.of(show.seat(0)), null)).get("bookingId").asLong();
        mvc.perform(post("/api/bookings/{id}/pay", bookingId).with(auth(c)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---------------------------------------------------------------- declined card & discounts

    @Test
    void declinedCardReturnsToHeldGivesDiscountBackAndCanBeRetried() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        String code = "DECL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Long codeId = fx.discountCode(code, "FLAT", "25", 5, 1);
        long bookingId = body(hold(c, show.showId(), List.of(show.seat(0)), code)
                .andExpect(jsonPath("$.discount").value(25.0))).get("bookingId").asLong();

        pay(c, bookingId, key(), "tok_decline")
                .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("PAYMENT_FAILED"));
        assertThat(fx.string("SELECT status FROM booking WHERE id = ?", bookingId)).isEqualTo("HELD");
        assertThat(fx.count("SELECT used_count FROM discount_code WHERE id = ?", codeId)).isZero();
        assertThat(fx.count("SELECT count(*) FROM booking WHERE id = ? AND discount_redeemed", bookingId)).isZero();
        assertThat(fx.string("SELECT status FROM payment WHERE booking_id = ?", bookingId)).isEqualTo("FAILED");

        pay(c, bookingId, key(), "tok_visa").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(fx.count("SELECT used_count FROM discount_code WHERE id = ?", codeId)).isEqualTo(1);
        assertThat(fx.count("SELECT count(*) FROM booking WHERE id = ? AND discount_redeemed", bookingId)).isEqualTo(1);
    }

    @Test
    void discountUsedUpWhileCustomerWasPayingIsRejectedBeforeCharging() throws Exception {
        TestShow show = fx.show(Duration.ofDays(3));
        String code = "ONCE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        fx.discountCode(code, "PERCENT", "10", 1, 1);
        Customer a = fx.customer();
        Customer b = fx.customer();
        long bookingA = body(hold(a, show.showId(), List.of(show.seat(0)), code)).get("bookingId").asLong();
        long bookingB = body(hold(b, show.showId(), List.of(show.seat(1)), code)).get("bookingId").asLong();

        pay(a, bookingA, key(), null).andExpect(status().isOk());
        int before = gateway.successfulCharges();
        pay(b, bookingB, key(), null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_DISCOUNT"));
        assertThat(gateway.successfulCharges()).isEqualTo(before);
        assertThat(fx.string("SELECT status FROM booking WHERE id = ?", bookingB)).isEqualTo("HELD");
    }

    @Test
    void invalidDiscountAtHoldTimeIs422() throws Exception {
        TestShow show = fx.show(Duration.ofDays(3));
        hold(fx.customer(), show.showId(), List.of(show.seat(0)), "EXPIRED10")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_DISCOUNT"));
    }

    // ---------------------------------------------------------------- confirm failure → auto refund

    @Test
    void chargeSucceedsButSeatsAreLostSoThePaymentIsRefundedAutomatically() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        long bookingId = body(hold(c, show.showId(), List.of(show.seat(0), show.seat(1)), null)).get("bookingId").asLong();

        // While the charge is in flight, the hold runs out (e.g. a very slow provider).
        gateway.beforeCharge(() -> fx.jdbc().update(
                "UPDATE seat_lock SET held_until = now() - interval '1 second' WHERE booking_id = ?", bookingId));

        pay(c, bookingId, key(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));

        assertThat(fx.string("SELECT status FROM booking WHERE id = ?", bookingId)).isEqualTo("EXPIRED");
        assertThat(fx.string("SELECT status FROM payment WHERE booking_id = ?", bookingId)).isEqualTo("REFUNDED");
        assertThat(fx.string("SELECT refund_reason || ':' || refund_status || ':' || refund_percent FROM booking WHERE id = ?", bookingId))
                .isEqualTo("CONFIRM_FAILED:SUCCESS:100");
        assertThat(fx.count("SELECT count(*) FROM seat_lock WHERE booking_id = ?", bookingId)).isZero();
        assertThat(fx.count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'REFUND_PROCESSED'",
                bookingId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- cancellation & refunds

    @Test
    void cancellingAHeldBookingReleasesSeatsWithoutMoney() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        long bookingId = body(hold(c, show.showId(), List.of(show.seat(0)), null)).get("bookingId").asLong();
        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).with(auth(c)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund").doesNotExist());
        assertThat(fx.count("SELECT count(*) FROM seat_lock WHERE booking_id = ?", bookingId)).isZero();
        hold(fx.customer(), show.showId(), List.of(show.seat(0)), null).andExpect(status().isCreated());
    }

    @Test
    void refundPercentFollowsTheStandardPolicyByHoursBeforeShow() throws Exception {
        assertCancelRefund(Duration.ofHours(30), 100);
        assertCancelRefund(Duration.ofHours(5), 50);
        assertCancelRefund(Duration.ofMinutes(90), 0);
    }

    @Test
    void theaterOverridePolicyIsUsed() throws Exception {
        // INOX uses "Flexible": ≥2h → 100%, otherwise 25%
        Customer c = fx.customer();
        TestShow show = fx.show(TestFixtures.INOX_THEATER_ID, Duration.ofMinutes(90));
        long bookingId = confirmedBooking(c, show, show.seat(0));
        double total = Double.parseDouble(fx.string("SELECT total_amount::text FROM booking WHERE id = ?", bookingId));
        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).with(auth(c)))
                .andExpect(jsonPath("$.refund.percentage").value(25))
                .andExpect(jsonPath("$.refund.amount").value(Math.round(total * 25) / 100.0));
    }

    private void assertCancelRefund(Duration startsIn, int expectedPercent) throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(startsIn);
        long bookingId = confirmedBooking(c, show, show.seat(0));
        String total = fx.string("SELECT total_amount::text FROM booking WHERE id = ?", bookingId);
        double expectedAmount = Math.round(Double.parseDouble(total) * expectedPercent) / 100.0;

        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).with(auth(c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.percentage").value(expectedPercent))
                .andExpect(jsonPath("$.refund.amount").value(expectedAmount))
                .andExpect(jsonPath("$.refund.status").value("SUCCESS"));
        String expectedPayment = expectedPercent == 100 ? "REFUNDED" : expectedPercent == 0 ? "SUCCESS" : "PARTIALLY_REFUNDED";
        assertThat(fx.string("SELECT status FROM payment WHERE booking_id = ?", bookingId)).isEqualTo(expectedPayment);
        assertThat(fx.count("SELECT count(*) FROM seat_lock WHERE booking_id = ?", bookingId)).isZero();
    }

    @Test
    void failedRefundStaysVisibleToAdmins() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        long bookingId = confirmedBooking(c, show, show.seat(0));
        gateway.failNextRefunds(1);
        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).with(auth(c)))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.status").value("FAILED"));
        mvc.perform(get("/api/admin/refunds").param("status", "FAILED").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.bookingId == " + bookingId + ")]").exists());
    }

    @Test
    void cancellingAfterTheShowStartedIsRejected() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        long bookingId = confirmedBooking(c, show, show.seat(0));
        fx.startShow(show.showId());
        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).with(auth(c)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_ALLOWED"));
    }

    @Test
    void cancellingTwiceIsInvalidState() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        long bookingId = body(hold(c, show.showId(), List.of(show.seat(0)), null)).get("bookingId").asLong();
        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).with(auth(c))).andExpect(status().isOk());
        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).with(auth(c)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_BOOKING_STATE"));
    }

    // ---------------------------------------------------------------- admin cancels a show

    @Test
    void adminCancellingAShowRefundsEveryoneInFullAndIsSafeToRerun() throws Exception {
        TestShow show = fx.show(Duration.ofHours(2));          // inside the 0% window of the normal policy
        Customer paid = fx.customer();
        Customer holding = fx.customer();
        long confirmed = confirmedBooking(paid, show, show.seat(0));
        long held = body(hold(holding, show.showId(), List.of(show.seat(1)), null)).get("bookingId").asLong();

        mvc.perform(post("/api/admin/shows/{id}/cancel", show.showId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newlyCancelled").value(true))
                .andExpect(jsonPath("$.bookingsProcessed").value(2));

        assertThat(fx.string("SELECT status FROM show WHERE id = ?", show.showId())).isEqualTo("CANCELLED");
        assertThat(fx.string("SELECT status FROM booking WHERE id = ?", confirmed)).isEqualTo("CANCELLED");
        assertThat(fx.string("SELECT status FROM booking WHERE id = ?", held)).isEqualTo("CANCELLED");
        assertThat(fx.string("SELECT refund_reason || ':' || refund_percent || ':' || refund_status FROM booking WHERE id = ?", confirmed))
                .isEqualTo("SHOW_CANCELLED:100:SUCCESS");
        assertThat(fx.count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'SHOW_CANCELLED'",
                confirmed)).isEqualTo(1);
        assertThat(fx.count("SELECT count(*) FROM seat_lock WHERE show_id = ?", show.showId())).isZero();

        mvc.perform(post("/api/admin/shows/{id}/cancel", show.showId()).with(admin()))
                .andExpect(jsonPath("$.newlyCancelled").value(false))
                .andExpect(jsonPath("$.bookingsProcessed").value(0));
        assertThat(fx.string("SELECT refund_status FROM booking WHERE id = ?", confirmed)).isEqualTo("SUCCESS");

        hold(fx.customer(), show.showId(), List.of(show.seat(2)), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOW_NOT_BOOKABLE"));
    }

    @Test
    void adminCancellingMidPaymentLeadsToAnAutomaticRefund() throws Exception {
        Customer c = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        long bookingId = body(hold(c, show.showId(), List.of(show.seat(0)), null)).get("bookingId").asLong();

        gateway.beforeCharge(() -> {
            try {
                mvc.perform(post("/api/admin/shows/{id}/cancel", show.showId()).with(admin())).andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        pay(c, bookingId, key(), null).andExpect(status().isConflict());

        assertThat(fx.string("SELECT status FROM booking WHERE id = ?", bookingId)).isEqualTo("CANCELLED");
        assertThat(fx.string("SELECT refund_reason || ':' || refund_status FROM booking WHERE id = ?", bookingId))
                .isEqualTo("CONFIRM_FAILED:SUCCESS");
        assertThat(fx.string("SELECT status FROM payment WHERE booking_id = ?", bookingId)).isEqualTo("REFUNDED");
    }

    // ---------------------------------------------------------------- ownership & history

    @Test
    void someoneElsesBookingIs404() throws Exception {
        Customer owner = fx.customer();
        Customer stranger = fx.customer();
        TestShow show = fx.show(Duration.ofDays(3));
        long bookingId = body(hold(owner, show.showId(), List.of(show.seat(0)), null)).get("bookingId").asLong();

        mvc.perform(get("/api/bookings/{id}", bookingId).with(auth(stranger)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
        pay(stranger, bookingId, key(), null).andExpect(status().isNotFound());
        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).with(auth(stranger))).andExpect(status().isNotFound());
    }

    @Test
    void historyPagesWithACursorNewestFirst() throws Exception {
        Customer c = fx.customer();
        long[] ids = new long[3];
        for (int i = 0; i < 3; i++) {
            TestShow show = fx.show(Duration.ofDays(3));
            ids[i] = body(hold(c, show.showId(), List.of(show.seat(0)), null)).get("bookingId").asLong();
        }
        JsonNode page1 = body(mvc.perform(get("/api/me/bookings").param("size", "2").with(auth(c))).andExpect(status().isOk()));
        assertThat(page1.get("items").size()).isEqualTo(2);
        assertThat(page1.get("items").get(0).get("bookingId").asLong()).isEqualTo(ids[2]);
        assertThat(page1.get("items").get(1).get("bookingId").asLong()).isEqualTo(ids[1]);
        assertThat(page1.get("hasNext").asBoolean()).isTrue();

        JsonNode page2 = body(mvc.perform(get("/api/me/bookings").param("size", "2")
                .param("cursor", page1.get("nextCursor").asText()).with(auth(c))));
        assertThat(page2.get("items").size()).isEqualTo(1);
        assertThat(page2.get("items").get(0).get("bookingId").asLong()).isEqualTo(ids[0]);
        assertThat(page2.get("hasNext").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private long confirmedBooking(Customer c, TestShow show, Long seatId) throws Exception {
        long bookingId = body(hold(c, show.showId(), List.of(seatId), null)).get("bookingId").asLong();
        pay(c, bookingId, key(), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        return bookingId;
    }

    private ResultActions hold(Customer c, Long showId, List<Long> seatIds, String code) throws Exception {
        String body = json.writeValueAsString(new java.util.HashMap<String, Object>() {{
            put("showId", showId);
            put("seatIds", seatIds);
            if (code != null) {
                put("discountCode", code);
            }
        }});
        return mvc.perform(post("/api/bookings").with(auth(c)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions pay(Customer c, long bookingId, String key, String token) throws Exception {
        var request = post("/api/bookings/{id}/pay", bookingId).with(auth(c)).header("Idempotency-Key", key);
        if (token != null) {
            request.contentType(MediaType.APPLICATION_JSON).content("{\"paymentToken\":\"" + token + "\"}");
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(Customer c) {
        return httpBasic(c.email(), TestFixtures.PASSWORD);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return httpBasic("admin@moviebooking.com", "Admin@123");
    }
}
