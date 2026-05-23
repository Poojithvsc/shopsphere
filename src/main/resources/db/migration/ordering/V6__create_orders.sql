CREATE TABLE ordering.orders (
    id                       UUID         PRIMARY KEY,
    customer_id              UUID         NOT NULL,
    status                   VARCHAR(32)  NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'CANCELLED')),
    total_amount             NUMERIC(19,4) NOT NULL,
    total_currency           VARCHAR(3)   NOT NULL,
    shipping_address         TEXT         NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_customer ON ordering.orders(customer_id);

CREATE TABLE ordering.order_line_items (
    id                  UUID          PRIMARY KEY,
    order_id            UUID          NOT NULL REFERENCES ordering.orders(id) ON DELETE CASCADE,
    product_id          UUID          NOT NULL,
    product_name        VARCHAR(255)  NOT NULL,
    unit_price_amount   NUMERIC(19,4) NOT NULL,
    unit_price_currency VARCHAR(3)    NOT NULL,
    qty                 INTEGER       NOT NULL CHECK (qty > 0),
    line_total_amount   NUMERIC(19,4) NOT NULL,
    line_total_currency VARCHAR(3)    NOT NULL,
    UNIQUE (order_id, product_id)
);

CREATE INDEX idx_order_line_items_order ON ordering.order_line_items(order_id);
