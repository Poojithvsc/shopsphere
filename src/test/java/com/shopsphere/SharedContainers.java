package com.shopsphere;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Postgres + Kafka containers shared across every {@code @SpringBootTest}. Starting fresh
 * containers per test class would cost ~10 seconds each AND make the Spring context cache incoherent:
 * two test classes with identical configuration would share a context (good), but each class's
 * {@code @Container} field would launch its own container with a different dynamic port. Beans
 * cached from the first class then point at a port the second class's container doesn't own.
 * <p>
 * Centralising the lifecycle here gives every test the same broker URL and lets Spring's context
 * cache do its job.
 */
public final class SharedContainers {

    private SharedContainers() {
    }

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    public static String kafkaBootstrap() {
        return KAFKA.getBootstrapServers();
    }
}
