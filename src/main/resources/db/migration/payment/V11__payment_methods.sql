-- Tokenized payment instruments. The raw PAN is NEVER stored: `vault_ref` holds an opaque
-- reference (a stand-in for an external vault handle), `last_four` keeps only the display digits.
-- One row per tokenize() call, so the same card yields a fresh token every time.
CREATE TABLE payment.payment_methods (
    token       UUID         PRIMARY KEY,
    vault_ref   TEXT         NOT NULL,
    last_four   TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
