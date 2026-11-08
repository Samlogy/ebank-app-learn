CREATE TABLE accounts (
    id          uuid         PRIMARY KEY,
    account_id  varchar(34)  NOT NULL,
    type        varchar(16)  NOT NULL CHECK (type IN ('SAVING', 'CURRENT', 'CRYPTO')),
    status      varchar(16)  NOT NULL CHECK (status IN ('CREATED', 'ACTIVE', 'DISABLED', 'CLOSED')),
    user_id     varchar(255) NOT NULL,
    currency    varchar(3)   NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL,
    CONSTRAINT uq_accounts_account_id UNIQUE (account_id)
);

CREATE INDEX ix_accounts_user_id ON accounts (user_id);

CREATE TABLE account_history (
    id            uuid         PRIMARY KEY,
    account_id    varchar(34)  NOT NULL REFERENCES accounts (account_id),
    action        varchar(16)  NOT NULL,
    status_before varchar(16),
    status_after  varchar(16)  NOT NULL,
    type_before   varchar(16),
    type_after    varchar(16)  NOT NULL,
    changed_by    varchar(255) NOT NULL,
    changed_at    timestamptz  NOT NULL
);

CREATE INDEX ix_account_history_account_id ON account_history (account_id, changed_at DESC);
