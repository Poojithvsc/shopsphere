-- Idempotency-Key dedupe for POST /api/v1/orders. The (customer_id, idempotency_key) primary key
-- is the sole arbiter of concurrent same-key requests: exactly one INSERT wins, the loser's whole
-- checkout transaction rolls back and then returns the winner's order_id. request_hash is a SHA-256
-- of the request payload (never the raw PAN), so the same key with a different body can be rejected.
CREATE TABLE ordering.idempotency_keys (
    customer_id      UUID         NOT NULL,
    idempotency_key  TEXT         NOT NULL,
    request_hash     TEXT         NOT NULL,
    order_id         UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, idempotency_key)
);
