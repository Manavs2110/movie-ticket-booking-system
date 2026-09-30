package com.moviebooking.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.dto.booking.HoldRequest;
import com.moviebooking.service.booking.BookingService;
import com.moviebooking.service.booking.SeatMapService;
import com.moviebooking.support.IntegrationTest;
import com.moviebooking.support.TestFixtures.TestShow;
import com.moviebooking.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seat map polling with ETag / 304 (LLD §9). */
class SeatMapIT extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;
    @Autowired BookingService bookingService;

    @Test
    void etagChangesOnHoldAndOnExpiryAnd304WhenUnchanged() throws Exception {
        TestShow show = fx.show(Duration.ofDays(3));

        MvcResult first = mvc.perform(get("/api/shows/{id}/seats", show.showId()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.seats.length()").value(40))
                .andExpect(jsonPath("$.bookable").value(true))
                .andExpect(jsonPath("$.nextChangeAt").doesNotExist())
                .andReturn();
        String etag0 = first.getResponse().getHeader(HttpHeaders.ETAG);

        mvc.perform(get("/api/shows/{id}/seats", show.showId()).header(HttpHeaders.IF_NONE_MATCH, etag0))
                .andExpect(status().isNotModified());

        Long bookingId = bookingService.hold(fx.customer().id(),
                new HoldRequest(show.showId(), List.of(show.seat(0)), null)).bookingId();

        MvcResult afterHold = mvc.perform(get("/api/shows/{id}/seats", show.showId()).header(HttpHeaders.IF_NONE_MATCH, etag0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[0].state").value("HELD"))
                .andExpect(jsonPath("$.nextChangeAt").exists())
                .andReturn();
        String etag1 = afterHold.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag1).isNotEqualTo(etag0);

        // The hold simply runs out: no write happens, yet the ETag must change back.
        fx.expireHold(bookingId);
        MvcResult afterExpiry = mvc.perform(get("/api/shows/{id}/seats", show.showId()).header(HttpHeaders.IF_NONE_MATCH, etag1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[0].state").value("AVAILABLE"))
                .andReturn();
        assertThat(afterExpiry.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(etag0);
    }

    @Test
    void layoutChangeBumpsTheEtagAndShowsBlockedSeats() throws Exception {
        TestShow show = fx.show(Duration.ofDays(3));
        String etag0 = mvc.perform(get("/api/shows/{id}/seats", show.showId())).andReturn()
                .getResponse().getHeader(HttpHeaders.ETAG);

        mvc.perform(put("/api/admin/screens/{s}/seats/{seat}", show.screenId(), show.seat(0))
                        .with(httpBasic("admin@moviebooking.com", "Admin@123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());

        fx.clearSeatMapCache(show.showId());      // layout edits show up once the 2 s micro-cache turns over
        mvc.perform(get("/api/shows/{id}/seats", show.showId()).header(HttpHeaders.IF_NONE_MATCH, etag0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[0].state").value("BLOCKED"));
    }

    @Test
    void microCacheServesPollsWithoutPostgresForTwoSecondsAndIsEvictedByAHold() throws Exception {
        TestShow show = fx.show(Duration.ofDays(3));
        Long bookingId = bookingService.hold(fx.customer().id(),
                new HoldRequest(show.showId(), List.of(show.seat(0)), null)).bookingId();
        mvc.perform(get("/api/shows/{id}/seats", show.showId()))
                .andExpect(jsonPath("$.seats[0].state").value("HELD"));      // built and cached

        // Change Postgres behind the app's back (no eviction): polls keep getting the cached map.
        fx.jdbc().update("UPDATE seat_lock SET held_until = now() - interval '1 second' WHERE booking_id = ?", bookingId);
        mvc.perform(get("/api/shows/{id}/seats", show.showId()))
                .andExpect(jsonPath("$.seats[0].state").value("HELD"));
        assertThat(fx.redis().getExpire(SeatMapService.cacheKey(show.showId()), java.util.concurrent.TimeUnit.MILLISECONDS))
                .isBetween(1L, 2_000L);

        // A hold on the show evicts it, so the acting user sees fresh state immediately.
        bookingService.hold(fx.customer().id(), new HoldRequest(show.showId(), List.of(show.seat(5)), null));
        mvc.perform(get("/api/shows/{id}/seats", show.showId()))
                .andExpect(jsonPath("$.seats[0].state").value("AVAILABLE"))
                .andExpect(jsonPath("$.seats[5].state").value("HELD"));
    }

    @Test
    void pricesReflectSeatType() throws Exception {
        TestShow show = fx.show(Duration.ofDays(3));
        String body = mvc.perform(get("/api/shows/{id}/seats", show.showId())).andReturn().getResponse().getContentAsString();
        // Row D is PREMIUM (×1.5 of the same base, same day)
        JsonNode seats = new ObjectMapper().readTree(body).get("seats");
        double regular = seats.get(0).get("price").asDouble();
        double premium = seats.get(35).get("price").asDouble();
        assertThat(seats.get(35).get("type").asText()).isEqualTo("PREMIUM");
        assertThat(premium).isEqualTo(regular * 1.5);
    }
}
