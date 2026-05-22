CREATE TABLE identity.refresh_tokens (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES identity.users(id),
    token_hash  VARCHAR(72)  NOT NULL,
    family_id   UUID         NOT NULL,
    parent_id   UUID         REFERENCES identity.refresh_tokens(id),
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_family ON identity.refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_user   ON identity.refresh_tokens(user_id);
