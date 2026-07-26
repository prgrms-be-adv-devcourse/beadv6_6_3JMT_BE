create table notification (
    id uuid primary key,
    recipient_id uuid not null,
    sequence bigint not null,
    event_id uuid not null,
    type varchar(50) not null,
    title varchar(255) not null,
    message varchar(2000) not null,
    reference_type varchar(50) not null,
    reference_id uuid not null,
    read_at timestamp,
    created_at timestamp not null,
    constraint uk_notification_recipient_sequence unique (recipient_id, sequence)
);

create index idx_notification_recipient_created_at
    on notification (recipient_id, created_at desc);

create index idx_notification_recipient_read_at
    on notification (recipient_id, read_at);

create table processed_event (
    event_id uuid not null,
    consumer_group varchar(100) not null,
    notification_id uuid not null,
    processed_at timestamp not null,
    primary key (event_id, consumer_group),
    constraint fk_processed_event_notification
        foreign key (notification_id) references notification (id)
);

create table notification_recipient_sequence (
    recipient_id uuid primary key,
    last_sequence bigint not null
);
