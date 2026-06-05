package com.shopsphere.ordering;

import com.shopsphere.SharedContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention sweep for {@code ordering.idempotency_keys} (#73, deferred from ADR-0014). A key older
 * than the configured TTL is deleted by the sweep; a key inside the window survives untouched.
 */
@SpringBootTest
class IdempotencyRetentionIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    IdempotencyRetention retention;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sweepRemovesExpiredKeysButKeepsFreshOnes() {
        UUID customer = UUID.randomUUID();
        String staleKey = "stale-" + UUID.randomUUID();
        String freshKey = "fresh-" + UUID.randomUUID();
        Instant now = Instant.now();

        insertKey(customer, staleKey, now.minus(Duration.ofHours(48)));
        insertKey(customer, freshKey, now);

        retention.sweep();

        assertThat(rowExists(customer, staleKey)).isFalse();
        assertThat(rowExists(customer, freshKey)).isTrue();
    }

    private void insertKey(UUID customer, String key, Instant createdAt) {
        jdbc.update(
                "INSERT INTO ordering.idempotency_keys "
                        + "(customer_id, idempotency_key, request_hash, order_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                customer, key, "hash", UUID.randomUUID(), Timestamp.from(createdAt));
    }

    private boolean rowExists(UUID customer, String key) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ordering.idempotency_keys "
                        + "WHERE customer_id = ? AND idempotency_key = ?",
                Integer.class, customer, key);
        return count != null && count > 0;
    }
}
