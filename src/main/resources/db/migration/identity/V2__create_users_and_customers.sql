CREATE TABLE identity.customers (
    id          UUID         PRIMARY KEY,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE identity.users (
    id              UUID         PRIMARY KEY,
    customer_id     UUID         NOT NULL UNIQUE REFERENCES identity.customers(id),
    email           VARCHAR(254) NOT NULL UNIQUE,
    password_hash   VARCHAR(72)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
