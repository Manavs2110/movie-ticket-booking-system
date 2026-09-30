package com.moviebooking.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The {@code booking-events} topic (LLD §10.3): keyed by booking id so one booking's events stay in order.
 * 6 partitions and replication 1 locally; 12+ and 3 (min.insync.replicas=2) in production.
 * Producer and consumer settings are in application.yml; retry and dead-letter topics come from
 * {@code @RetryableTopic} on the consumer.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Bean
    public NewTopic bookingEventsTopic(AppProperties props) {
        return TopicBuilder.name(props.outbox().topic()).partitions(6).replicas(1).build();
    }
}
