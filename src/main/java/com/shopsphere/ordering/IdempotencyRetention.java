package com.shopsphere.ordering;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Bounds the growth of {@code ordering.idempotency_keys}. Every keyed {@code POST /api/v1/orders}
 * inserts a row and the placement path never removes it, so without this sweep the table and its
 * primary-key index grow forever — the honest limitation deferred in ADR-0014 (issue #73).
 * <p>
 * The sweep deletes any claim older than {@code shopsphere.ordering.idempotency.ttl}. That TTL only
 * has to outlast the window in which a client might legitimately retry the same request (seconds to
 * minutes), so the 24h default leaves a wide safety margin while still capping retention. Dropping
 * an old claim is safe: a retry arriving after expiry simply places a fresh order, which is the
 * correct behaviour once no in-flight request could still be referencing the key.
 */
@Component
class IdempotencyRetention {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetention.class);

    private final IdempotencyKeys keys;
    private final Duration ttl;
    private final Clock clock;

    IdempotencyRetention(IdempotencyKeys keys,
                         @Value("${shopsphere.ordering.idempotency.ttl:PT24H}") Duration ttl,
                         Clock clock) {
        this.keys = keys;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * Deletes idempotency claims older than the TTL and returns how many were removed. Package-private
     * so it can be driven deterministically from a test; the schedule below only delegates here.
     */
    int sweep() {
        Instant cutoff = clock.instant().minus(ttl);
        int removed = keys.deleteOlderThan(cutoff);
        if (removed > 0) {
            log.info("Idempotency retention sweep removed {} expired key(s) older than {}", removed, cutoff);
        }
        return removed;
    }

    @Scheduled(fixedDelayString = "${shopsphere.ordering.idempotency.sweep-interval:PT1H}")
    void scheduledSweep() {
        sweep();
    }
}
