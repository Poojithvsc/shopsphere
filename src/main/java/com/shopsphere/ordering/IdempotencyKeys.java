package com.shopsphere.ordering;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@code ordering.idempotency_keys}. The {@code (customer_id, idempotency_key)}
 * primary key makes {@link #claim} the concurrency arbiter: the first caller to insert wins, every
 * other concurrent caller's insert raises {@code DuplicateKeyException} and rolls its transaction
 * back. No application-level locking is involved.
 */
@Component
class IdempotencyKeys {

    private final JdbcTemplate jdbc;

    IdempotencyKeys(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims {@code (customerId, key)} for {@code orderId} within the caller's transaction. Throws
     * {@code org.springframework.dao.DuplicateKeyException} if the pair is already claimed.
     */
    void claim(UUID customerId, String key, String requestHash, UUID orderId) {
        jdbc.update(
                "INSERT INTO ordering.idempotency_keys "
                        + "(customer_id, idempotency_key, request_hash, order_id) VALUES (?, ?, ?, ?)",
                customerId, key, requestHash, orderId);
    }

    Optional<PriorRequest> find(UUID customerId, String key) {
        return jdbc.query(
                "SELECT request_hash, order_id FROM ordering.idempotency_keys "
                        + "WHERE customer_id = ? AND idempotency_key = ?",
                (rs, rowNum) -> new PriorRequest(
                        rs.getString("request_hash"), rs.getObject("order_id", UUID.class)),
                customerId, key).stream().findFirst();
    }

    /**
     * Deletes every claim created strictly before {@code cutoff} and returns how many rows went.
     * Used by the retention sweep ({@link IdempotencyRetention}) to keep the table from growing
     * unbounded; the cutoff only needs to predate any legitimate client-retry window.
     */
    int deleteOlderThan(Instant cutoff) {
        return jdbc.update(
                "DELETE FROM ordering.idempotency_keys WHERE created_at < ?",
                Timestamp.from(cutoff));
    }

    record PriorRequest(String requestHash, UUID orderId) {
    }
}
