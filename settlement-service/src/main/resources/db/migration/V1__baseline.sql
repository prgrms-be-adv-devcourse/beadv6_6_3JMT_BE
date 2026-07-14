--
-- settlement-service baseline schema
--
-- 이 서비스가 실제로 소유하는 테이블 + Spring Batch 인프라 테이블만 정의한다.
-- 스키마 접두사를 명시하지 않고 JDBC URL의 currentSchema(settlement_service)에 위임한다.
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

--
-- Spring Batch 인프라 테이블
--

CREATE TABLE batch_job_instance (
    job_instance_id bigint NOT NULL,
    version bigint,
    job_name character varying(100) NOT NULL,
    job_key character varying(32) NOT NULL
);

CREATE SEQUENCE batch_job_instance_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE batch_job_execution (
    job_execution_id bigint NOT NULL,
    version bigint,
    job_instance_id bigint NOT NULL,
    create_time timestamp without time zone NOT NULL,
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    status character varying(10),
    exit_code character varying(2500),
    exit_message character varying(2500),
    last_updated timestamp without time zone
);

CREATE SEQUENCE batch_job_execution_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE batch_job_execution_context (
    job_execution_id bigint NOT NULL,
    short_context character varying(2500) NOT NULL,
    serialized_context text
);

CREATE TABLE batch_job_execution_params (
    job_execution_id bigint NOT NULL,
    parameter_name character varying(100) NOT NULL,
    parameter_type character varying(100) NOT NULL,
    parameter_value character varying(2500),
    identifying character(1) NOT NULL
);

CREATE TABLE batch_step_execution (
    step_execution_id bigint NOT NULL,
    version bigint NOT NULL,
    step_name character varying(100) NOT NULL,
    job_execution_id bigint NOT NULL,
    create_time timestamp without time zone NOT NULL,
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    status character varying(10),
    commit_count bigint,
    read_count bigint,
    filter_count bigint,
    write_count bigint,
    read_skip_count bigint,
    write_skip_count bigint,
    process_skip_count bigint,
    rollback_count bigint,
    exit_code character varying(2500),
    exit_message character varying(2500),
    last_updated timestamp without time zone
);

CREATE SEQUENCE batch_step_execution_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE batch_step_execution_context (
    step_execution_id bigint NOT NULL,
    short_context character varying(2500) NOT NULL,
    serialized_context text
);

--
-- settlement-service 도메인 테이블
--

CREATE TABLE settlement (
    settlement_id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    calculated_at timestamp(6) without time zone NOT NULL,
    canceled_at timestamp(6) without time zone,
    confirmed_at timestamp(6) without time zone,
    failed_reason character varying(1000),
    fee_total_amount numeric(12,2) NOT NULL,
    paid_at timestamp(6) without time zone,
    payout_reference character varying(100),
    payout_status character varying(255) NOT NULL,
    period_end date NOT NULL,
    period_start date NOT NULL,
    product_count integer NOT NULL,
    refund_amount numeric(12,2),
    seller_id uuid NOT NULL,
    settlement_batch_id uuid,
    settlement_status character varying(255) NOT NULL,
    settlement_total_amount numeric(12,2) NOT NULL,
    total_amount numeric(12,2) NOT NULL,
    CONSTRAINT settlement_payout_status_check CHECK (((payout_status)::text = ANY (ARRAY[('NOT_READY'::character varying)::text, ('READY'::character varying)::text, ('PAYOUT_REQUESTED'::character varying)::text, ('PAYOUT_ON_HOLD'::character varying)::text, ('PAID'::character varying)::text]))),
    CONSTRAINT settlement_settlement_status_check CHECK (((settlement_status)::text = ANY (ARRAY[('PENDING_APPROVAL'::character varying)::text, ('SETTLEMENT_ON_HOLD'::character varying)::text, ('APPROVED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);

CREATE TABLE settlement_batch (
    batch_id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    batch_no character varying(100) NOT NULL,
    executed_at timestamp(6) without time zone,
    failure_reason character varying(1000),
    period_end date NOT NULL,
    period_start date NOT NULL,
    status character varying(255) NOT NULL,
    trigger_type character varying(255) NOT NULL,
    CONSTRAINT settlement_batch_status_check CHECK (((status)::text = ANY (ARRAY[('PROCESSING'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT settlement_batch_trigger_type_check CHECK (((trigger_type)::text = ANY (ARRAY[('SCHEDULED'::character varying)::text, ('MANUAL'::character varying)::text])))
);

CREATE TABLE settlement_detail (
    settlement_detail_id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    fee_amount numeric(12,2) NOT NULL,
    fee_rate numeric(5,4) NOT NULL,
    line_amount numeric(12,2) NOT NULL,
    line_settlement_amount numeric(12,2) NOT NULL,
    line_type character varying(255) NOT NULL,
    occurred_at timestamp(6) without time zone NOT NULL,
    order_product_id uuid,
    settlement_id uuid NOT NULL,
    CONSTRAINT settlement_detail_line_type_check CHECK (((line_type)::text = ANY (ARRAY[('SALE'::character varying)::text, ('REFUND'::character varying)::text, ('ADJUSTMENT'::character varying)::text])))
);

CREATE TABLE settlement_outbox_event (
    event_id uuid NOT NULL,
    settlement_batch_id uuid NOT NULL,
    aggregate_type character varying(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type character varying(100) NOT NULL,
    topic character varying(255) NOT NULL,
    payload text NOT NULL,
    status character varying(20) NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    occurred_at timestamp without time zone NOT NULL,
    last_attempted_at timestamp without time zone,
    last_failure_reason character varying(1000),
    failed_at timestamp without time zone,
    published_at timestamp without time zone,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    CONSTRAINT chk_settlement_outbox_retry_count CHECK ((retry_count >= 0)),
    CONSTRAINT chk_settlement_outbox_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PUBLISHED'::character varying)::text, ('FAILED'::character varying)::text])))
);

CREATE TABLE settlement_source_line (
    settlement_source_line_id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    event_id uuid NOT NULL,
    line_type character varying(30) CONSTRAINT settlement_source_line_event_type_not_null NOT NULL,
    line_amount numeric(12,2) NOT NULL,
    occurred_at timestamp(6) without time zone NOT NULL,
    order_id uuid,
    order_product_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    settlement_id uuid,
    CONSTRAINT settlement_source_line_line_type_check CHECK (((line_type)::text = ANY (ARRAY['PAID'::text, 'REFUND'::text])))
);

--
-- PRIMARY KEY / UNIQUE
--

ALTER TABLE ONLY batch_job_instance
    ADD CONSTRAINT batch_job_instance_pkey PRIMARY KEY (job_instance_id);

ALTER TABLE ONLY batch_job_instance
    ADD CONSTRAINT job_inst_un UNIQUE (job_name, job_key);

ALTER TABLE ONLY batch_job_execution
    ADD CONSTRAINT batch_job_execution_pkey PRIMARY KEY (job_execution_id);

ALTER TABLE ONLY batch_job_execution_context
    ADD CONSTRAINT batch_job_execution_context_pkey PRIMARY KEY (job_execution_id);

ALTER TABLE ONLY batch_step_execution
    ADD CONSTRAINT batch_step_execution_pkey PRIMARY KEY (step_execution_id);

ALTER TABLE ONLY batch_step_execution_context
    ADD CONSTRAINT batch_step_execution_context_pkey PRIMARY KEY (step_execution_id);

ALTER TABLE ONLY settlement
    ADD CONSTRAINT settlement_pkey PRIMARY KEY (settlement_id);

ALTER TABLE ONLY settlement_batch
    ADD CONSTRAINT settlement_batch_pkey PRIMARY KEY (batch_id);

ALTER TABLE ONLY settlement_batch
    ADD CONSTRAINT uk49w5gmo3stsjq44xvybxn2fau UNIQUE (batch_no);

ALTER TABLE ONLY settlement_detail
    ADD CONSTRAINT settlement_detail_pkey PRIMARY KEY (settlement_detail_id);

ALTER TABLE ONLY settlement_outbox_event
    ADD CONSTRAINT settlement_outbox_event_pkey PRIMARY KEY (event_id);

ALTER TABLE ONLY settlement_source_line
    ADD CONSTRAINT settlement_source_line_pkey PRIMARY KEY (settlement_source_line_id);

ALTER TABLE ONLY settlement_source_line
    ADD CONSTRAINT uk7un5j0j13x9w7yaqlu6g2omt8 UNIQUE (event_id);

--
-- INDEX
--

CREATE INDEX idx_settlement_outbox_aggregate_id ON settlement_outbox_event USING btree (aggregate_id);

CREATE INDEX idx_settlement_outbox_batch_status_occurred ON settlement_outbox_event USING btree (settlement_batch_id, status, occurred_at, event_id);

CREATE INDEX idx_settlement_outbox_status_attempted_occurred ON settlement_outbox_event USING btree (status, last_attempted_at, occurred_at, event_id);

--
-- FOREIGN KEY (서비스 내부 참조만)
--

ALTER TABLE ONLY batch_job_execution
    ADD CONSTRAINT job_inst_exec_fk FOREIGN KEY (job_instance_id) REFERENCES batch_job_instance(job_instance_id);

ALTER TABLE ONLY batch_job_execution_context
    ADD CONSTRAINT job_exec_ctx_fk FOREIGN KEY (job_execution_id) REFERENCES batch_job_execution(job_execution_id);

ALTER TABLE ONLY batch_job_execution_params
    ADD CONSTRAINT job_exec_params_fk FOREIGN KEY (job_execution_id) REFERENCES batch_job_execution(job_execution_id);

ALTER TABLE ONLY batch_step_execution
    ADD CONSTRAINT job_exec_step_fk FOREIGN KEY (job_execution_id) REFERENCES batch_job_execution(job_execution_id);

ALTER TABLE ONLY batch_step_execution_context
    ADD CONSTRAINT step_exec_ctx_fk FOREIGN KEY (step_execution_id) REFERENCES batch_step_execution(step_execution_id);

ALTER TABLE ONLY settlement_detail
    ADD CONSTRAINT fk6v53alv8ajhplgsd464u4o9sk FOREIGN KEY (settlement_id) REFERENCES settlement(settlement_id);
