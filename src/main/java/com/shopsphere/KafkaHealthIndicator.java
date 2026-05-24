package com.shopsphere;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Composite-health contributor for Kafka. Spring Boot ships no Kafka health indicator out of the
 * box, so {@code /actuator/health} would report UP even with the broker down. This bean queries the
 * cluster with a short, bounded timeout and reports DOWN when the broker is unreachable, which —
 * combined with the auto-configured {@code db} indicator — makes the composite health honest.
 * <p>
 * Bean name {@code "kafka"} so Actuator registers it under the {@code kafka} health key.
 */
@Component("kafka")
class KafkaHealthIndicator implements HealthIndicator {

    private static final long TIMEOUT_MS = 2000;

    private final Map<String, Object> adminProps;

    KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.adminProps = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        // Bound the probe so a dead broker fails fast instead of blocking the health endpoint.
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TIMEOUT_MS);
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TIMEOUT_MS);
    }

    @Override
    public Health health() {
        try (AdminClient admin = AdminClient.create(adminProps)) {
            DescribeClusterResult cluster = admin.describeCluster();
            String clusterId = cluster.clusterId().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            int nodeCount = cluster.nodes().get(TIMEOUT_MS, TimeUnit.MILLISECONDS).size();
            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("nodeCount", nodeCount)
                    .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
