-- Spring Modulith JPA event publication registry (transactional outbox).
-- Lives in the default JPA schema (catalog) because the modulith
-- JpaEventPublication @Entity has no schema declaration.
CREATE TABLE catalog.event_publication (
    id                UUID         NOT NULL,
    listener_id       TEXT         NOT NULL,
    event_type        TEXT         NOT NULL,
    serialized_event  TEXT         NOT NULL,
    publication_date  TIMESTAMP    NOT NULL,
    completion_date   TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx
    ON catalog.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_completion_date_idx
    ON catalog.event_publication (completion_date);
