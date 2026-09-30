package com.moviebooking.integration;

import com.moviebooking.support.IntegrationTest;
import com.moviebooking.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Security rules, error format, registration and the public/admin catalog endpoints. */
class ApiSecurityAndCatalogIT extends IntegrationTest {

    private static final String ADMIN = "admin@moviebooking.com";
    private static final String ADMIN_PW = "Admin@123";

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;

    // ---------------------------------------------------------------- security

    @Test
    void browsingIsPublic() throws Exception {
        mvc.perform(get("/api/cities"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(jsonPath("$[0].name").exists());
        mvc.perform(get("/api/movies/1")).andExpect(status().isOk()).andExpect(jsonPath("$.genres").isArray());
    }

    @Test
    void bookingNeedsLoginAndReturnsJson401() throws Exception {
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/auth/me").with(httpBasic(ADMIN, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCallingAdminApiGets403() throws Exception {
        TestFixtures.Customer c = fx.customer();
        mvc.perform(get("/api/admin/refund-policies").with(httpBasic(c.email(), TestFixtures.PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void eleventhHoldRequestInAMinuteIsRateLimited() throws Exception {
        TestFixtures.Customer c = fx.customer();
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/bookings").with(httpBasic(c.email(), TestFixtures.PASSWORD))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());                          // counted, then validated
        }
        mvc.perform(post("/api/bookings").with(httpBasic(c.email(), TestFixtures.PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // limits are per endpoint: reading bookings still works
        mvc.perform(get("/api/me/bookings").with(httpBasic(c.email(), TestFixtures.PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void registrationAlwaysCreatesACustomerAndMeReturnsTheRole() throws Exception {
        String email = "New-" + UUID.randomUUID() + "@Example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"fullName\":\"Riya\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(get("/api/auth/me").with(httpBasic(email.toLowerCase(), "secret123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CUSTOMER"));
        mvc.perform(get("/api/auth/me").with(httpBasic(ADMIN, ADMIN_PW)))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"fullName\":\"Riya\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void validationErrorsListTheFields() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.email").exists())
                .andExpect(jsonPath("$.details.password").exists())
                .andExpect(jsonPath("$.details.fullName").exists())
                .andExpect(jsonPath("$.path").value("/api/auth/register"))
                .andExpect(jsonPath("$.timestamp").exists());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---------------------------------------------------------------- browse

    @Test
    void moviesInCityAndTheatersNearMe() throws Exception {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);
        mvc.perform(get("/api/cities/1/movies").param("date", tomorrow.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", greaterThan(0)))
                .andExpect(jsonPath("$.page").value(0));
        mvc.perform(get("/api/cities/1/movies").param("date", tomorrow.toString()).param("language", "hindi"))
                .andExpect(jsonPath("$.items[*].language", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.equalTo("Hindi"))));

        // near Ashok Nagar: INOX Garuda (~0 km) must come before PVR Orion (~6 km)
        mvc.perform(get("/api/movies/1/shows").param("cityId", "1").param("date", tomorrow.toString())
                        .param("lat", "12.9700").param("lng", "77.6090"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("INOX Garuda"))
                .andExpect(jsonPath("$.items[0].distanceKm").value(0.0))
                .andExpect(jsonPath("$.items[0].showtimes.length()", greaterThan(0)));
    }

    @Test
    void browseRejectsBadPagingAndUnknownCity() throws Exception {
        mvc.perform(get("/api/cities/1/movies").param("size", "500")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/cities/9999/movies")).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- admin catalog

    @Test
    void adminBuildsATheaterScreenAndShowThenOverlapIsRejected() throws Exception {
        String theater = mvc.perform(post("/api/admin/theaters").with(httpBasic(ADMIN, ADMIN_PW))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cityId\":2,\"name\":\"Test Plex " + UUID.randomUUID() + "\",\"address\":\"Somewhere\","
                                + "\"latitude\":19.1,\"longitude\":72.8}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long theaterId = com.jayway.jsonpath.JsonPath.<Integer>read(theater, "$.id");

        String screen = mvc.perform(post("/api/admin/theaters/{id}/screens", theaterId).with(httpBasic(ADMIN, ADMIN_PW))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Audi 1\",\"rows\":[{\"rowLabel\":\"A\",\"seatCount\":8,\"seatType\":\"REGULAR\"},"
                                + "{\"rowLabel\":\"B\",\"seatCount\":6,\"seatType\":\"PREMIUM\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seatCount").value(14))
                .andReturn().getResponse().getContentAsString();
        long screenId = com.jayway.jsonpath.JsonPath.<Integer>read(screen, "$.id");

        String start = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).plusDays(5).withHour(18).withMinute(0)
                .withSecond(0).withNano(0).toOffsetDateTime().toString();
        String show = mvc.perform(post("/api/admin/shows").with(httpBasic(ADMIN, ADMIN_PW)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieId\":3,\"screenId\":" + screenId + ",\"startTime\":\"" + start
                                + "\",\"regularPrice\":250,\"premiumPrice\":400}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.regularPrice").value(250.0))
                .andExpect(jsonPath("$.premiumPrice").value(400.0))
                .andExpect(jsonPath("$.weekendMultiplier").value(1.25))                // default
                .andReturn().getResponse().getContentAsString();
        long showId = com.jayway.jsonpath.JsonPath.<Integer>read(show, "$.id");

        // seat map: row A (regular) from regularPrice, row B (premium) from premiumPrice;
        // the weekend multiplier (if the date is a weekend) applies to both, so the ratio is always 400 / 250
        String map = mvc.perform(get("/api/shows/{id}/seats", showId)).andReturn().getResponse().getContentAsString();
        double regular = com.jayway.jsonpath.JsonPath.<Double>read(map, "$.seats[0].price");
        double premium = com.jayway.jsonpath.JsonPath.<Double>read(map, "$.seats[8].price");
        org.assertj.core.api.Assertions.assertThat(premium / regular).isEqualTo(400.0 / 250.0);

        // premium price can still change while nobody has booked
        mvc.perform(put("/api/admin/shows/{id}", showId).with(httpBasic(ADMIN, ADMIN_PW))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"premiumPrice\":450}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premiumPrice").value(450.0))
                .andExpect(jsonPath("$.regularPrice").value(250.0));

        // 120-min movie + 15-min buffer: a show 1 hour later on the same screen overlaps
        String overlapping = ZonedDateTime.parse(start).plusHours(1).toOffsetDateTime().toString();
        mvc.perform(post("/api/admin/shows").with(httpBasic(ADMIN, ADMIN_PW)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieId\":3,\"screenId\":" + screenId + ",\"startTime\":\"" + overlapping + "\",\"regularPrice\":250,\"premiumPrice\":400}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOW_OVERLAP"));

        String past = ZonedDateTime.now().minusDays(1).toOffsetDateTime().toString();
        mvc.perform(post("/api/admin/shows").with(httpBasic(ADMIN, ADMIN_PW)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieId\":3,\"screenId\":" + screenId + ",\"startTime\":\"" + past + "\",\"regularPrice\":250,\"premiumPrice\":400}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void showPricingIsValidatedAndWeekendMultiplierCanBeSetPerShow() throws Exception {
        String start = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).plusDays(9).withHour(10).withMinute(0)
                .withSecond(0).withNano(0).toOffsetDateTime().toString();
        // premiumPrice is required
        mvc.perform(post("/api/admin/shows").with(httpBasic(ADMIN, ADMIN_PW)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieId\":3,\"screenId\":1,\"startTime\":\"" + start + "\",\"regularPrice\":200}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.premiumPrice").exists());
        // weekend multiplier out of range
        mvc.perform(post("/api/admin/shows").with(httpBasic(ADMIN, ADMIN_PW)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieId\":3,\"screenId\":1,\"startTime\":\"" + start
                                + "\",\"regularPrice\":200,\"premiumPrice\":300,\"weekendMultiplier\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.weekendMultiplier").exists());
        // the old global pricing API is gone
        mvc.perform(get("/api/admin/pricing").with(httpBasic(ADMIN, ADMIN_PW))).andExpect(status().isNotFound());
    }

    @Test
    void refundPolicyCrudKeepsExactlyOneDefault() throws Exception {
        String created = mvc.perform(post("/api/admin/refund-policies").with(httpBasic(ADMIN, ADMIN_PW))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Temp\",\"makeDefault\":false,\"rules\":[{\"minHoursBefore\":12,\"refundPercent\":80},"
                                + "{\"minHoursBefore\":0,\"refundPercent\":10}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rules[0].minHoursBefore").value(12))
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.<Integer>read(created, "$.id");

        // same thresholds again in an update must not trip the unique (policy, threshold) constraint
        mvc.perform(put("/api/admin/refund-policies/{id}", id).with(httpBasic(ADMIN, ADMIN_PW))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Temp\",\"makeDefault\":false,\"rules\":[{\"minHoursBefore\":12,\"refundPercent\":90}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(1))
                .andExpect(jsonPath("$.rules[0].refundPercent").value(90));

        mvc.perform(post("/api/admin/refund-policies").with(httpBasic(ADMIN, ADMIN_PW))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dup\",\"makeDefault\":false,\"rules\":[{\"minHoursBefore\":1,\"refundPercent\":1},"
                                + "{\"minHoursBefore\":1,\"refundPercent\":2}]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/admin/refund-policies").with(httpBasic(ADMIN, ADMIN_PW))).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(fx.count("SELECT count(*) FROM refund_policy WHERE is_default"))
                .isEqualTo(1);
    }
}
