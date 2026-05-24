package com.shopsphere;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaHealthIndicatorTests {

    @Test
    void reportsDownWhenBrokerUnreachable() {
        // Port 1 is never a Kafka broker; the bounded probe must surface DOWN rather than hang.
        KafkaAdmin admin = new KafkaAdmin(Map.of("bootstrap.servers", "localhost:1"));
        KafkaHealthIndicator indicator = new KafkaHealthIndicator(admin);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
