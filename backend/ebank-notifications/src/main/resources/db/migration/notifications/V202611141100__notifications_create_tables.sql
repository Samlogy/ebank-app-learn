-- One row per user and event. Account and transaction IDs are plain values: no foreign keys
-- into other modules' tables.
CREATE TABLE notification (
    id              uuid           PRIMARY KEY,
    user_id         varchar(255)   NOT NULL,
    type            varchar(32)    NOT NULL,
    source_event_id uuid           NOT NULL,
    source_id       varchar(64),
    account_id      varchar(34),
    counterparty    varchar(34),
    amount          numeric(30, 8),
    currency        varchar(3),
    created_at      timestamptz    NOT NULL,
    read_at         timestamptz,
    -- The outbox delivers at least once: a replayed event must not notify twice.
    CONSTRAINT uq_notification_source UNIQUE (source_event_id, user_id)
);

CREATE INDEX ix_notification_user ON notification (user_id, created_at DESC);
CREATE INDEX ix_notification_user_unread ON notification (user_id) WHERE read_at IS NULL;

-- No row means the defaults: email on, SMS off.
CREATE TABLE notification_preference (
    user_id       varchar(255) PRIMARY KEY,
    email_enabled boolean      NOT NULL,
    sms_enabled   boolean      NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL
);
