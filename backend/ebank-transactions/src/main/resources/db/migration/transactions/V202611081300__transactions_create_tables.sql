-- Account IDs are plain values with no foreign keys: modules never reach into each other's tables.
CREATE TABLE transfers (
    id                  uuid           PRIMARY KEY,
    transaction_id      varchar(40)    NOT NULL,
    sender_account_id   varchar(34)    NOT NULL,
    receiver_account_id varchar(34)    NOT NULL,
    amount              numeric(30, 8) NOT NULL CHECK (amount > 0),
    currency            varchar(3)     NOT NULL,
    reference           varchar(140),
    idempotency_key     varchar(128)   NOT NULL,
    initiated_by        varchar(255)   NOT NULL,
    status              varchar(16)    NOT NULL CHECK (status IN ('PENDING', 'DONE', 'CANCELLED')),
    failure_reason      varchar(64),
    created_at          timestamptz    NOT NULL,
    updated_at          timestamptz    NOT NULL,
    version             bigint         NOT NULL,
    CONSTRAINT uq_transfers_transaction_id UNIQUE (transaction_id),
    CONSTRAINT uq_transfers_idempotency UNIQUE (initiated_by, idempotency_key),
    CONSTRAINT ck_transfers_different_accounts CHECK (sender_account_id <> receiver_account_id)
);

CREATE INDEX ix_transfers_sender ON transfers (sender_account_id, created_at DESC);
CREATE INDEX ix_transfers_receiver ON transfers (receiver_account_id, created_at DESC);

CREATE TABLE ledger_entries (
    id          uuid           PRIMARY KEY,
    transfer_id uuid           NOT NULL REFERENCES transfers (id),
    account_id  varchar(34)    NOT NULL,
    type        varchar(8)     NOT NULL CHECK (type IN ('DEBIT', 'CREDIT')),
    amount      numeric(30, 8) NOT NULL CHECK (amount > 0),
    currency    varchar(3)     NOT NULL,
    created_at  timestamptz    NOT NULL,
    CONSTRAINT uq_ledger_transfer_type UNIQUE (transfer_id, type)
);

CREATE INDEX ix_ledger_account ON ledger_entries (account_id);
