CREATE TABLE notification_setting (
    setting_id UUID PRIMARY KEY,
    recipient_id UUID NOT NULL,
    category VARCHAR(30) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_notification_setting_recipient_category
        UNIQUE (recipient_id, category)
);
