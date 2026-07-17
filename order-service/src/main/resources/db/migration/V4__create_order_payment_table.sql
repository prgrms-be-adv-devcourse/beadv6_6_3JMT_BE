CREATE TABLE IF NOT EXISTS order_payment (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    order_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    buyer_id uuid NOT NULL,
    pg_tx_id character varying(100) NOT NULL,
    payment_method character varying(30) NOT NULL,
    provider character varying(50) NOT NULL,
    approved_amount integer NOT NULL,
    approved_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT order_payment_pkey PRIMARY KEY (id),
    CONSTRAINT uk_order_payment_order_id UNIQUE (order_id),
    CONSTRAINT uk_order_payment_payment_id UNIQUE (payment_id),
    CONSTRAINT uk_order_payment_pg_tx_id UNIQUE (pg_tx_id)
);
