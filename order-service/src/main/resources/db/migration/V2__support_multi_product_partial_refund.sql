ALTER TABLE public."order"
    DROP CONSTRAINT order_order_status_check;

ALTER TABLE public."order"
    ADD CONSTRAINT order_order_status_check
        CHECK (order_status IN (
            'PENDING',
            'PAID',
            'PARTIALLY_REFUNDED',
            'FAILED',
            'CANCELED',
            'REFUNDED'
        ));

ALTER TABLE public.order_product
    DROP CONSTRAINT order_product_order_product_status_check;

ALTER TABLE public.order_product
    ADD CONSTRAINT order_product_order_product_status_check
        CHECK (order_product_status IN (
            'PENDING',
            'PAID',
            'REFUND_REQUESTED',
            'FAILED',
            'CANCELED',
            'REFUNDED'
        ));

ALTER TABLE public.order_refund
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN retryable boolean NOT NULL DEFAULT false;

ALTER TABLE public.order_refund
    ALTER COLUMN failure_reason TYPE text;

ALTER TABLE public.order_refund
    DROP CONSTRAINT order_refund_status_check;

UPDATE public.order_refund
SET status = 'UNKNOWN'
WHERE status = 'TIMEOUT';

ALTER TABLE public.order_refund
    ADD CONSTRAINT order_refund_status_check
        CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    ADD CONSTRAINT order_refund_total_amount_check
        CHECK (total_refund_amount > 0),
    ADD CONSTRAINT order_refund_reconciliation_attempt_check
        CHECK (reconciliation_attempt >= 0);

ALTER TABLE public.order_refund_product
    DROP CONSTRAINT uk_order_refund_product_order_product;

ALTER TABLE public.order_refund_product
    ADD CONSTRAINT order_refund_product_amount_check
        CHECK (refund_amount > 0);

CREATE INDEX idx_order_refund_payment
    ON public.order_refund (payment_id);

CREATE INDEX idx_order_refund_order
    ON public.order_refund (order_id);

CREATE INDEX idx_order_refund_status_next_check
    ON public.order_refund (status, next_check_at);

CREATE INDEX idx_order_refund_manual_review
    ON public.order_refund (manual_review_required);

CREATE INDEX idx_order_refund_product_order_product
    ON public.order_refund_product (order_product_id);
