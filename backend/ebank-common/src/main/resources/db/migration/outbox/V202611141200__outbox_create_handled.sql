-- Which handlers already processed an event. Lets each handler succeed or fail on its own:
-- a retry only re-runs the handlers that are missing here.
CREATE TABLE outbox_event_handled (
    event_id   uuid         NOT NULL REFERENCES outbox_event (id) ON DELETE CASCADE,
    handler    varchar(255) NOT NULL,
    handled_at timestamptz  NOT NULL,
    PRIMARY KEY (event_id, handler)
);
