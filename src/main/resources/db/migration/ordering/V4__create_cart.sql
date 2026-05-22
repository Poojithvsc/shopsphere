CREATE TABLE ordering.carts (
    id           UUID         PRIMARY KEY,
    customer_id  UUID         NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE ordering.cart_line_items (
    id          UUID         PRIMARY KEY,
    cart_id     UUID         NOT NULL REFERENCES ordering.carts(id) ON DELETE CASCADE,
    product_id  UUID         NOT NULL,
    qty         INTEGER      NOT NULL CHECK (qty > 0),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (cart_id, product_id)
);

CREATE INDEX idx_cart_line_items_cart ON ordering.cart_line_items(cart_id);
