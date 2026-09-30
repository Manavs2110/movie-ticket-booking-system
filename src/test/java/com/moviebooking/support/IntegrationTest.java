package com.moviebooking.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Base for integration tests: real PostgreSQL, Redis and Kafka via Testcontainers, started once per JVM and
 * shared by every test class (Spring also reuses the application context between them).
 * <ul>
 *   <li>Scheduled jobs are off; tests trigger the outbox publisher directly.</li>
 *   <li>The Kafka listener is off: the outbox row's status is the dedupe, shared by every consumer, so only
 *       {@code EventsIT} (which turns it on) may consume. Each context still gets its own consumer group.</li>
 *   <li>Time-dependent cases move timestamps in the database instead of sleeping.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "app.notification.retry-delay-ms=200",
        "app.rate-limit.default-per-minute=1000",
        "app.notification.listener-enabled=false"})
@AutoConfigureMockMvc
@Import({TestFixtures.class, HookedPaymentGateway.Config.class, RecordingNotificationSender.Config.class})
public abstract class IntegrationTest {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    public static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        String group = "notification-" + UUID.randomUUID().toString().substring(0, 8);
        registry.add("app.notification.consumer-group", () -> group);
    }
}
