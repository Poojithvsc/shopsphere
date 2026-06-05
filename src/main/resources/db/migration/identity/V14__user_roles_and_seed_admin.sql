-- Phase 17: authorization roles on users + one seeded admin.
-- Roles are stored denormalised as a comma-separated list (e.g. 'USER' or 'USER,ADMIN'). The role
-- set is fixed and tiny and there is no role-management UI, so a join table would be premature
-- (ADR-0017). Existing users default to the base 'USER' role; the JWT roles claim is derived from
-- this column and mapped to ROLE_* authorities.
ALTER TABLE identity.users ADD COLUMN roles VARCHAR(100) NOT NULL DEFAULT 'USER';

-- Seed a single admin. The customer row exists only to satisfy the users.customer_id FK; the admin
-- is an operator, not a shopper. The password hash is bcrypt(cost 10) of the DEV-ONLY password
-- 'admin12345admin' (documented in ADR-0017) — it MUST be rotated before any non-local deployment.
INSERT INTO identity.customers (id, created_at)
VALUES ('99999999-9999-9999-9999-999999999999', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity.users (id, customer_id, email, password_hash, roles, created_at)
VALUES (
    '99999999-9999-9999-9999-999999999990',
    '99999999-9999-9999-9999-999999999999',
    'admin@shopsphere.local',
    '$2a$10$lkQeGMFGhwPNDxRJ4sq7FeTTeA7WCDUWb/0IuhnIOb5FjquB7x4NW',
    'USER,ADMIN',
    now()
)
ON CONFLICT (email) DO NOTHING;
