--
-- payment-service baseline schema
--
-- 이 서비스가 실제로 소유하는 테이블만 정의한다. 스키마 접두사를 명시하지 않고
-- JDBC URL의 currentSchema(payment_service)에 위임한다.
--

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';
SET default_table_access_method = heap;

CREATE TABLE payment (
    id uuid NOT NULL,
    approved_amount integer,
    approved_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    failed_at timestamp(6) with time zone,
    failure_code character varying(100),
    failure_reason text,
    is_test boolean NOT NULL,
    order_id uuid NOT NULL,
    payment_method character varying(30) NOT NULL,
    pg_tx_id character varying(255) NOT NULL,
    provider character varying(30) NOT NULL,
    refunded_at timestamp(6) with time zone,
    request_payload jsonb,
    requested_at timestamp(6) with time zone,
    response_payload jsonb,
    status character varying(20) NOT NULL,
    total_amount integer NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT payment_status_check CHECK (((status)::text = ANY (ARRAY[('READY'::character varying)::text, ('REQUESTED'::character varying)::text, ('PAID'::character varying)::text, ('FAILED'::character varying)::text, ('REFUNDING'::character varying)::text, ('REFUNDED'::character varying)::text, ('UNKNOWN'::character varying)::text])))
);

CREATE TABLE refund (
    id uuid NOT NULL,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    order_product_id uuid,
    payment_id uuid NOT NULL,
    reason text,
    refund_amount integer NOT NULL,
    requested_at timestamp(6) with time zone NOT NULL,
    status character varying(20) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT refund_status_check CHECK (((status)::text = ANY (ARRAY[('REQUESTED'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text])))
);

CREATE TABLE order_snapshot (
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    buyer_id uuid NOT NULL,
    total_amount integer NOT NULL,
    source character varying(10) NOT NULL,
    order_created_at timestamp(6) with time zone NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT order_snapshot_source_check CHECK (((source)::text = ANY (ARRAY['EVENT'::text, 'GRPC'::text])))
);

ALTER TABLE ONLY payment
    ADD CONSTRAINT payment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refund
    ADD CONSTRAINT refund_pkey PRIMARY KEY (id);

ALTER TABLE ONLY order_snapshot
    ADD CONSTRAINT order_snapshot_pkey PRIMARY KEY (id);

ALTER TABLE ONLY order_snapshot
    ADD CONSTRAINT order_snapshot_order_id_key UNIQUE (order_id);

CREATE UNIQUE INDEX uk_payment_pg_tx_id ON payment USING btree (pg_tx_id);

CREATE UNIQUE INDEX uk_payment_order_paid ON payment USING btree (order_id) WHERE ((status)::text = 'PAID'::text);
