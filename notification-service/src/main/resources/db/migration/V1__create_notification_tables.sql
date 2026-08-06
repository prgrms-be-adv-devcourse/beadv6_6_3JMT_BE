CREATE TABLE notification (
    notification_id UUID PRIMARY KEY,
    recipient_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    category VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    link_url VARCHAR(500),
    reference_type VARCHAR(50),
    reference_id UUID,
    is_read BOOLEAN NOT NULL,
    read_at TIMESTAMP WITH TIME ZONE,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deduplication_key VARCHAR(200) NOT NULL UNIQUE
);

CREATE INDEX idx_notification_recipient_created_at ON notification (recipient_id, created_at);
CREATE INDEX idx_notification_recipient_read_created_at ON notification (recipient_id, is_read, created_at);
CREATE INDEX idx_notification_expires_at ON notification (expires_at);

CREATE TABLE notification_processed_event (
    processed_event_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_notification_processed_event_id_group UNIQUE (event_id, consumer_group)
);
