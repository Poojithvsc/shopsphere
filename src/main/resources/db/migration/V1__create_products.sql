CREATE TABLE products (
    id                   UUID            PRIMARY KEY,
    name                 VARCHAR(200)    NOT NULL,
    description          VARCHAR(2000)   NOT NULL,
    unit_price_amount    NUMERIC(19, 4)  NOT NULL,
    unit_price_currency  VARCHAR(3)      NOT NULL,
    available_qty        INTEGER         NOT NULL CHECK (available_qty >= 0)
);

INSERT INTO products (id, name, description, unit_price_amount, unit_price_currency, available_qty) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Aurora Mechanical Keyboard',  'Hot-swappable 75% mechanical keyboard with PBT keycaps.',     8499.0000, 'INR', 12),
    ('22222222-2222-2222-2222-222222222222', 'Nimbus Wireless Mouse',       'Lightweight 60g wireless mouse with 4K Hz polling.',          5999.0000, 'INR',  8),
    ('33333333-3333-3333-3333-333333333333', 'Vertex 27-inch 4K Monitor',      '27-inch IPS panel, 4K UHD, 144Hz, HDR400.',                  42999.0000, 'INR',  4);
