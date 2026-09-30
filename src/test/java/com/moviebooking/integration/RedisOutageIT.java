package com.moviebooking.integration;

import com.moviebooking.common.AppException;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.common.RedisGuard;
import com.moviebooking.dto.booking.HoldRequest;
import com.moviebooking.service.booking.BookingService;
import com.moviebooking.support.HookedPaymentGateway;
import com.moviebooking.support.IntegrationTest;
import com.moviebooking.support.RecordingNotificationSender;
import com.moviebooking.support.TestFixtures;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HLD §9.5: Redis down → the circuit breaker opens, seat locking goes straight to Postgres (still exactly one
 * winner), caches fall back to Postgres, rate limiting fails open. Uses its own Redis container, which it stops,
 * so it gets its own application context.
 */
@SpringBootTest(properties = {"app.scheduling.enabled=false", "app.rate-limit.default-per-minute=1000"})
@AutoConfigureMockMvc
@Import({TestFixtures.class, HookedPaymentGateway.Config.class, RecordingNotificationSender.Config.class})
class RedisOutageIT {

    static final GenericContainer<?> OWN_REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        OWN_REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationTest.POSTGRES::getJdbcUrl);   // shared Postgres and Kafka
        registry.add("spring.datasource.username", IntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationTest.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", IntegrationTest.KAFKA::getBootstrapServers);
        registry.add("app.notification.consumer-group", () -> "notification-redis-outage");
        registry.add("spring.data.redis.host", OWN_REDIS::getHost);
        registry.add("spring.data.redis.port", () -> OWN_REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fx;
    @Autowired BookingService bookingService;
    @Autowired RedisGuard redisGuard;

    @AfterAll
    static void cleanup() {
        if (OWN_REDIS.isRunning()) {
            OWN_REDIS.stop();
        }
    }

    @Test
    void withRedisDownBrowsingWorksAndLockingFallsBackToPostgresWithOneWinner() throws Exception {
        mvc.perform(get("/api/cities")).andExpect(status().isOk());       // warms the cache
        TestFixtures.TestShow show = fx.show(Duration.ofDays(2));
        List<TestFixtures.Customer> customers = fx.customers(20);

        OWN_REDIS.stop();

        mvc.perform(get("/api/cities")).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").exists());
        mvc.perform(get("/api/movies/1")).andExpect(status().isOk());
        mvc.perform(get("/api/shows/{id}/seats", show.showId())).andExpect(status().isOk());

        // 20 users race for one seat with no Redis filter: Postgres alone still picks exactly one.
        ExecutorService pool = Executors.newFixedThreadPool(customers.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ErrorCode>> results = new ArrayList<>();
        try {
            for (TestFixtures.Customer c : customers) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        bookingService.hold(c.id(), new HoldRequest(show.showId(), List.of(show.seat(0)), null));
                        return null;
                    } catch (AppException e) {
                        return e.code();
                    }
                }));
            }
            go.countDown();
            int winners = 0;
            for (Future<ErrorCode> f : results) {
                ErrorCode code = f.get(60, TimeUnit.SECONDS);
                if (code == null) {
                    winners++;
                } else {
                    assertThat(code).isEqualTo(ErrorCode.SEATS_UNAVAILABLE);
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(redisGuard.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // The HTTP path still works end to end: rate limiter fails open, hold goes to Postgres.
        TestFixtures.Customer c = fx.customer();
        mvc.perform(post("/api/bookings").with(httpBasic(c.email(), TestFixtures.PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showId\":" + show.showId() + ",\"seatIds\":[" + show.seat(1) + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"));
    }
}
