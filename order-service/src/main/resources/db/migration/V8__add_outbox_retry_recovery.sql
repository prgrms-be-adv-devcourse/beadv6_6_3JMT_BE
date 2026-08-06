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
