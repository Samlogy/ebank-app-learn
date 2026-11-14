-- Transactional outbox: events are written in the same transaction as the change that caused them,
-- then delivered to their handlers by OutboxRelay. Delivery is at least once.
CREATE TABLE outbox_event (
    id              uuid         PRIMARY KEY,
    event_type      varchar(128) NOT NULL,
    aggregate_id    varchar(128) NOT NULL,
    payload         jsonb        NOT NULL,
    occurred_at     timestamptz  NOT NULL,
    status          varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts        integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL,
    last_error      varchar(255),
    published_at    timestamptz
);

-- The relay only ever reads pending rows, oldest first.
CREATE INDEX ix_outbox_event_pending ON outbox_event (next_attempt_at, occurred_at) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_event_published ON outbox_event (published_at) WHERE status = 'PUBLISHED';
