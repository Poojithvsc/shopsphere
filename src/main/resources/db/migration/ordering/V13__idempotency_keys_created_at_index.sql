-- Retention sweep (#73, deferred from ADR-0014) deletes idempotency claims by age:
-- DELETE FROM ordering.idempotency_keys WHERE created_at < <cutoff>. The primary key is on
-- (customer_id, idempotency_key), so that predicate would otherwise scan the whole table on every
-- sweep. This index lets the periodic DELETE find expired rows without a full scan.
CREATE INDEX idx_idempotency_keys_created_at ON ordering.idempotency_keys (created_at);
