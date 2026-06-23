create table if not exists order_outbox (
    id char(36) primary key,
    event_type varchar(50) not null,
    aggregate_type varchar(50) not null,
    aggregate_id char(36) not null,
    payload text not null,
    occurred_at timestamp not null,
    status varchar(20) not null,
    created_at timestamp,
    updated_at timestamp
);

create index if not exists idx_order_outbox_status_occurred_at
    on order_outbox (status, occurred_at);

create index if not exists idx_order_outbox_aggregate
    on order_outbox (aggregate_type, aggregate_id);
