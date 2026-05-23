package com.shopsphere.common;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Per-consumer event deduplication. Each consumer (catalog, ordering, payment) owns its own
 * {@code <schema>.processed_events(consumer_id, event_id)} table. Calling {@link #markProcessed}
 * inside the same transaction as the consumer's effect makes the effect idempotent under Kafka
 * at-least-once redelivery: if the row already exists, the call returns {@code false} and the
 * caller skips its side effect.
 */
public final class ProcessedEvents {

    private final JdbcTemplate jdbc;
    private final String schema;

    public ProcessedEvents(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /**
     * Returns {@code true} if this is the first time the (consumer, event) pair has been recorded,
     * {@code false} if it was already present. The insert is part of the caller's transaction; a
     * rollback removes the dedupe marker so the consumer can retry.
     */
    public boolean markProcessed(String consumerId, UUID eventId) {
        try {
            int rows = jdbc.update(
                    "INSERT INTO " + schema + ".processed_events (consumer_id, event_id) VALUES (?, ?)",
                    consumerId, eventId);
            return rows == 1;
        } catch (DuplicateKeyException already) {
            return false;
        }
    }
}
