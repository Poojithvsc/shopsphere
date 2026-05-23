CREATE TABLE payment.processed_events (
    consumer_id   TEXT         NOT NULL,
    event_id      UUID         NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_id, event_id)
);
