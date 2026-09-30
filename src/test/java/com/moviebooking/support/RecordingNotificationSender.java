package com.moviebooking.support;

import com.moviebooking.dto.notification.BookingEventMessage;
import com.moviebooking.service.notification.NotificationSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what the Kafka consumer "sent", and can simulate a provider outage for chosen events. */
public class RecordingNotificationSender implements NotificationSender {

    private final List<BookingEventMessage> sent = new CopyOnWriteArrayList<>();
    private final Set<Long> failing = ConcurrentHashMap.newKeySet();

    @Override
    public void send(BookingEventMessage event) {
        if (failing.contains(event.eventId())) {
            throw new IllegalStateException("provider down for event " + event.eventId());
        }
        sent.add(event);
    }

    public long timesSent(long eventId) {
        return sent.stream().filter(e -> e.eventId() == eventId).count();
    }

    public void failFor(long eventId) {
        failing.add(eventId);
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        RecordingNotificationSender recordingNotificationSender() {
            return new RecordingNotificationSender();
        }
    }
}
