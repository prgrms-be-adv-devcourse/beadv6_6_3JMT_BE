-- Add optimistic locking and the one-product-per-refund persistence model.

ALTER TABLE ONLY "order"
    ADD COLUMN version bigint DEFAULT 0 NOT NULL;

ALTER TABLE ONLY order_product
    ADD COLUMN version bigint DEFAULT 0 NOT NULL;

ALTER TABLE ONLY "order"
    DROP CONSTRAINT order_order_status_check;

ALTER TABLE ONLY "order"
    ADD CONSTRAINT order_order_status_check CHECK (
        order_status::text = ANY (ARRAY[
            'PENDING'::text,
            'PAID'::text,
            'FAILED'::text,
            'CANCELED'::text,
            'REFUND_REQUESTED'::text,
            'PARTIAL_REFUNDED'::text,
            'REFUNDED'::text
        ])
    );

ALTER TABLE ONLY order_product
    DROP CONSTRAINT order_product_order_product_status_check;

ALTER TABLE ONLY order_product
    ADD CONSTRAINT order_product_order_product_status_check CHECK (
        order_product_status::text = ANY (ARRAY[
            'PENDING'::text,
            'PAID'::text,
            'FAILED'::text,
            'CANCELED'::text,
            'REFUND_REQUESTED'::text,
            'REFUNDED'::text
        ])
    );

CREATE TABLE order_refund (
    id uuid NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    order_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    buyer_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    total_refund_amount integer NOT NULL,
    check_count integer DEFAULT 0 NOT NULL,
    next_check_at timestamp(6) without time zone,
    requested_at timestamp(6) without time zone NOT NULL,
    completed_at timestamp(6) without time zone,
    failed_at timestamp(6) without time zone,
    failure_code character varying(100),
    failure_reason text,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    CONSTRAINT order_refund_pkey PRIMARY KEY (id),
    CONSTRAINT uk_order_refund_id_amount UNIQUE (id, total_refund_amount),
    CONSTRAINT order_refund_status_check CHECK (
        status::text = ANY (ARRAY[
            'REQUESTED'::text,
            'COMPLETED'::text,
            'FAILED'::text,
            'DLQ'::text
        ])
    ),
    CONSTRAINT ck_order_refund_positive_values CHECK (
        total_refund_amount > 0 AND check_count >= 0
    ),
    CONSTRAINT fk_order_refund_order FOREIGN KEY (order_id) REFERENCES "order" (id),
    CONSTRAINT fk_order_refund_payment FOREIGN KEY (payment_id) REFERENCES order_payment (payment_id)
);

CREATE TABLE order_refund_product (
    id uuid NOT NULL,
    order_refund_id uuid NOT NULL,
    order_product_id uuid NOT NULL,
    refund_amount integer NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT order_refund_product_pkey PRIMARY KEY (id),
    CONSTRAINT ck_order_refund_product_positive_amount CHECK (refund_amount > 0),
    CONSTRAINT uk_order_refund_product_refund UNIQUE (order_refund_id),
    CONSTRAINT uk_order_refund_product_order_product UNIQUE (order_product_id),
    CONSTRAINT fk_order_refund_product_refund
        FOREIGN KEY (order_refund_id, refund_amount)
        REFERENCES order_refund (id, total_refund_amount),
    CONSTRAINT fk_order_refund_product_order_product
        FOREIGN KEY (order_product_id) REFERENCES order_product (id)
);

CREATE INDEX idx_order_refund_status_next_check
    ON order_refund (status, next_check_at);

CREATE INDEX idx_order_refund_order_status
    ON order_refund (order_id, status);

CREATE INDEX idx_order_refund_payment
    ON order_refund (payment_id);
