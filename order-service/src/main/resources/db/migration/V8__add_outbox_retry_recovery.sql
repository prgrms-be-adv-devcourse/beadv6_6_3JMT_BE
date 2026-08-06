ALTER TABLE order_outbox_event
    ADD COLUMN next_attempt_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_attempt_at timestamp(6) without time zone,
    ADD COLUMN last_error text,
    ADD COLUMN lease_owner character varying(100),
    ADD COLUMN lease_until timestamp(6) without time zone;

UPDATE order_outbox_event
SET next_attempt_at = occurred_at
WHERE status = 'PENDING';

UPDATE order_outbox_event
SET next_attempt_at = NULL
WHERE status <> 'PENDING';

CREATE INDEX idx_order_outbox_event_publishable
    ON order_outbox_event (status, next_attempt_at, lease_until, occurred_at);

CREATE TABLE order_outbox_redrive_history (
    id uuid NOT NULL,
    event_id uuid NOT NULL,
    requested_by uuid NOT NULL,
    reason character varying(500) NOT NULL,
    previous_retry_count integer NOT NULL,
    previous_last_attempt_at timestamp(6) without time zone,
    previous_last_error text,
    requested_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT order_outbox_redrive_history_pkey PRIMARY KEY (id),
    CONSTRAINT fk_order_outbox_redrive_history_event
        FOREIGN KEY (event_id) REFERENCES order_outbox_event (event_id)
);

CREATE INDEX idx_order_outbox_redrive_history_event_requested
    ON order_outbox_redrive_history (event_id, requested_at DESC);
