-- Chat between a customer and the assistant, then an advisor. User IDs are Keycloak subjects:
-- no foreign keys outside this module.
CREATE TABLE conversation (
    id             uuid         PRIMARY KEY,
    customer_id    varchar(255) NOT NULL,
    state          varchar(16)  NOT NULL CHECK (state IN ('BOT', 'WAITING_ADVISOR', 'WITH_ADVISOR', 'RESOLVED')),
    handoff_reason varchar(16)  CHECK (handoff_reason IN ('USER_REQUEST', 'LLM_ESCALATION')),
    advisor_id     varchar(255),
    summary        varchar(1000),
    last_seq       bigint       NOT NULL,
    handoff_at     timestamptz,
    assigned_at    timestamptz,
    resolved_at    timestamptz,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL,
    CONSTRAINT ck_conversation_advisor CHECK ((state = 'WITH_ADVISOR') = (advisor_id IS NOT NULL) OR state = 'RESOLVED')
);

-- At most one open conversation per customer.
CREATE UNIQUE INDEX uq_conversation_open_per_customer ON conversation (customer_id) WHERE state <> 'RESOLVED';
CREATE INDEX ix_conversation_customer ON conversation (customer_id, created_at DESC);
CREATE INDEX ix_conversation_queue ON conversation (handoff_at) WHERE state = 'WAITING_ADVISOR';
CREATE INDEX ix_conversation_advisor ON conversation (advisor_id) WHERE state = 'WITH_ADVISOR';

CREATE TABLE message (
    id                uuid         PRIMARY KEY,
    conversation_id   uuid         NOT NULL REFERENCES conversation (id),
    seq               bigint       NOT NULL,
    sender_type       varchar(16)  NOT NULL CHECK (sender_type IN ('CUSTOMER', 'ASSISTANT', 'ADVISOR', 'SYSTEM')),
    sender_id         varchar(255),
    type              varchar(16)  NOT NULL CHECK (type IN ('TEXT', 'SYSTEM')),
    content           varchar(4000) NOT NULL,
    client_message_id varchar(64),
    created_at        timestamptz  NOT NULL,
    deleted_at        timestamptz,
    CONSTRAINT uq_message_seq UNIQUE (conversation_id, seq),
    -- A retried send with the same client-generated ID is stored once.
    CONSTRAINT uq_message_client_id UNIQUE (conversation_id, sender_id, client_message_id)
);

CREATE TABLE conversation_participant (
    conversation_id uuid         NOT NULL REFERENCES conversation (id),
    user_id         varchar(255) NOT NULL,
    role            varchar(16)  NOT NULL CHECK (role IN ('CUSTOMER', 'ADVISOR')),
    last_read_seq   bigint       NOT NULL,
    joined_at       timestamptz  NOT NULL,
    PRIMARY KEY (conversation_id, user_id)
);

-- Every tool call, handoff, assignment and resolution. No message content.
CREATE TABLE chatbot_audit (
    id              uuid         PRIMARY KEY,
    occurred_at     timestamptz  NOT NULL,
    actor_id        varchar(255),
    conversation_id uuid         NOT NULL,
    action          varchar(32)  NOT NULL,
    detail          varchar(255)
);

CREATE INDEX ix_chatbot_audit_conversation ON chatbot_audit (conversation_id, occurred_at);
