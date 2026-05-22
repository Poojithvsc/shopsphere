CREATE TABLE catalog.stock_reservations (
    id          UUID         PRIMARY KEY,
    order_id    UUID         NOT NULL,
    product_id  UUID         NOT NULL REFERENCES catalog.products(id),
    qty         INTEGER      NOT NULL CHECK (qty > 0),
    status      VARCHAR(16)  NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (order_id, product_id)
);

CREATE INDEX idx_stock_reservations_order  ON catalog.stock_reservations(order_id);
CREATE INDEX idx_stock_reservations_status ON catalog.stock_reservations(status);
