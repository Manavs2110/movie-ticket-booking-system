package com.moviebooking.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviebooking.dto.booking.HoldRequest;
import com.moviebooking.dto.notification.BookingEventMessage;
import com.moviebooking.model.notification.EventType;
import com.moviebooking.service.booking.BookingService;
import com.moviebooking.service.notification.internal.NotificationConsumer;
import com.moviebooking.service.notification.internal.OutboxPublisher;
import com.moviebooking.support.IntegrationTest;
import com.moviebooking.support.RecordingNotificationSender;
import com.moviebooking.support.TestFixtures.Customer;
import com.moviebooking.support.TestFixtures.TestShow;
import com.moviebooking.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Outbox → publisher → Kafka → notification consumer, on real Kafka (LLD §10, §15 "Events (v2)"). */
class EventsIT extends IntegrationTest {

    private static final Duration KAFKA_WAIT = Duration.ofSeconds(30);

    @Autowired OutboxPublisher publisher;
    @Autowired NotificationConsumer consumer;
    @Autowired BookingService bookingService;
    @Autowired RecordingNotificationSender sender;
    @Autowired TestFixtures fx;
    @Autowired KafkaTemplate<String, BookingEventMessage> kafka;
    @Autowired ObjectMapper objectMapper;
    @Value("${app.outbox.topic}") String topic;

    /** The only test class whose context consumes from Kafka (see {@link IntegrationTest}). */
    @DynamicPropertySource
    static void listenerOn(DynamicPropertyRegistry registry) {
        registry.add("app.notification.listener-enabled", () -> "true");
    }

    @Test
    void confirmationTravelsOutboxToKafkaToExactlyOneNotification() {
        long bookingId = confirmedBooking(fx.customer(), fx.show(Duration.ofDays(3)));
        long eventId = eventId(bookingId, "BOOKING_CONFIRMED");
        assertThat(status(eventId)).isEqualTo("NOT_STARTED");

        publisher.publishDue();

        // the consumer may already have finished by now
        assertThat(status(eventId)).isIn("IN_QUEUE", "PROCESSED");
        assertThat(fx.count("SELECT count(*) FROM outbox_event WHERE id = ? AND sent_at IS NOT NULL", eventId)).isEqualTo(1);
        await().atMost(KAFKA_WAIT).until(() -> "PROCESSED".equals(status(eventId)));
        assertThat(fx.count("SELECT count(*) FROM outbox_event WHERE id = ? AND processed_at IS NOT NULL", eventId)).isEqualTo(1);
        assertThat(sender.timesSent(eventId)).isEqualTo(1);
    }

    @Test
    void theSameEventDeliveredTwiceIsNotifiedOnce() throws Exception {
        long bookingId = confirmedBooking(fx.customer(), fx.show(Duration.ofDays(3)));
        long eventId = eventId(bookingId, "BOOKING_CONFIRMED");
        publisher.publishDue();
        await().atMost(KAFKA_WAIT).until(() -> "PROCESSED".equals(status(eventId)));

        // Kafka is at-least-once (e.g. a consumer crash before the offset commit): the same message again
        var row = fx.jdbc().queryForMap("SELECT aggregate_id, created_at, payload::text AS payload FROM outbox_event WHERE id = ?",
                eventId);
        kafka.send(topic, String.valueOf(bookingId), new BookingEventMessage(eventId, EventType.BOOKING_CONFIRMED,
                bookingId, ((java.sql.Timestamp) row.get("created_at")).toInstant(),
                objectMapper.readTree((String) row.get("payload")))).get();

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> sender.timesSent(eventId) == 1);
        assertThat(status(eventId)).isEqualTo("PROCESSED");
    }

    @Test
    void reminderIsAFutureDatedRowPublishedWhenDue() {
        long bookingId = confirmedBooking(fx.customer(), fx.show(Duration.ofDays(3)));
        long reminderId = eventId(bookingId, "SHOW_REMINDER");
        assertThat(fx.count("""
                SELECT count(*) FROM outbox_event o JOIN booking b ON b.id = o.aggregate_id JOIN show s ON s.id = b.show_id
                WHERE o.id = ? AND o.next_attempt_at = s.start_time - interval '2 hours'
                """, reminderId)).as("due at show start − 2 h").isEqualTo(1);

        publisher.publishDue();
        assertThat(status(reminderId)).as("not due yet").isEqualTo("NOT_STARTED");

        fx.jdbc().update("UPDATE outbox_event SET next_attempt_at = now() WHERE id = ?", reminderId);
        publisher.publishDue();
        await().atMost(KAFKA_WAIT).until(() -> "PROCESSED".equals(status(reminderId)));
        assertThat(sender.timesSent(reminderId)).isEqualTo(1);
    }

    @Test
    void bookingInsideTheLeadTimeGetsNoReminder() {
        long bookingId = confirmedBooking(fx.customer(), fx.show(Duration.ofMinutes(90)));
        assertThat(fx.count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'SHOW_REMINDER'",
                bookingId)).isZero();
    }

    @Test
    void cancellingABookingCancelsItsReminder() {
        Customer c = fx.customer();
        long bookingId = confirmedBooking(c, fx.show(Duration.ofDays(3)));
        long reminderId = eventId(bookingId, "SHOW_REMINDER");

        bookingService.cancel(c.id(), bookingId);

        assertThat(status(reminderId)).isEqualTo("CANCELLED");
        fx.jdbc().update("UPDATE outbox_event SET next_attempt_at = now() WHERE id = ?", reminderId);
        publisher.publishDue();
        assertThat(status(reminderId)).as("never published").isEqualTo("CANCELLED");
    }

    @Test
    void reminderAlreadyInKafkaIsSkippedIfTheBookingWasCancelled() {
        Customer c = fx.customer();
        long bookingId = confirmedBooking(c, fx.show(Duration.ofDays(3)));
        long reminderId = eventId(bookingId, "SHOW_REMINDER");
        bookingService.cancel(c.id(), bookingId);
        // force it through anyway, as if it had been published before the cancel
        fx.jdbc().update("UPDATE outbox_event SET status = 'NOT_STARTED', next_attempt_at = now() WHERE id = ?", reminderId);
        publisher.publishDue();

        await().atMost(KAFKA_WAIT).until(() -> "PROCESSED".equals(status(reminderId)));   // handled: checked and skipped
        assertThat(sender.timesSent(reminderId)).isZero();
    }

    @Test
    void consumerFailureGoesThroughRetryTopicsToTheDeadLetterTopic() {
        long bookingId = confirmedBooking(fx.customer(), fx.show(Duration.ofDays(3)));
        long eventId = eventId(bookingId, "BOOKING_CONFIRMED");
        sender.failFor(eventId);
        int deadBefore = consumer.deadLetteredCount();

        publisher.publishDue();

        await().atMost(KAFKA_WAIT).until(() -> consumer.deadLetteredCount() > deadBefore);
        assertThat(sender.timesSent(eventId)).isZero();
        // PROCESSED was rolled back with each failed attempt
        assertThat(status(eventId)).isEqualTo("IN_QUEUE");
    }

    @Test
    void kafkaDownDoesNotAffectBookingsAndEventsAreDeliveredOnceItIsBack() {
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        long bookingId;
        long eventId;
        try {
            bookingId = confirmedBooking(fx.customer(), fx.show(Duration.ofDays(3)));   // booking still succeeds
            eventId = eventId(bookingId, "BOOKING_CONFIRMED");
            publisher.publishDue();
            assertThat(fx.string("SELECT status || ':' || attempts FROM outbox_event WHERE id = ?", eventId))
                    .isEqualTo("NOT_STARTED:1");
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        fx.jdbc().update("UPDATE outbox_event SET next_attempt_at = now() WHERE id = ?", eventId);
        await().atMost(KAFKA_WAIT).until(() -> {
            publisher.publishDue();
            return !"NOT_STARTED".equals(status(eventId));
        });
        await().atMost(KAFKA_WAIT).until(() -> sender.timesSent(eventId) == 1);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> sender.timesSent(eventId) == 1);
    }

    // ---------------------------------------------------------------- helpers

    private long confirmedBooking(Customer c, TestShow show) {
        long id = bookingService.hold(c.id(), new HoldRequest(show.showId(), List.of(show.seat(0)), null)).bookingId();
        bookingService.pay(c.id(), id, UUID.randomUUID().toString(), null);
        return id;
    }

    private long eventId(long bookingId, String type) {
        return fx.jdbc().queryForObject("SELECT id FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Long.class, bookingId, type);
    }

    private String status(long eventId) {
        return fx.string("SELECT status FROM outbox_event WHERE id = ?", eventId);
    }
}
