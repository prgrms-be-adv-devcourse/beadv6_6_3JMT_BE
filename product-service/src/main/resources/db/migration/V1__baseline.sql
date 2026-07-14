--
-- PostgreSQL database dump
--


-- Dumped from database version 18.4
-- Dumped by pg_dump version 18.4

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: order_service; Type: SCHEMA; Schema: -; Owner: order_service
--

CREATE SCHEMA order_service;


ALTER SCHEMA order_service OWNER TO order_service;

--
-- Name: payment_service; Type: SCHEMA; Schema: -; Owner: payment_service
--

CREATE SCHEMA payment_service;


ALTER SCHEMA payment_service OWNER TO payment_service;

--
-- Name: product_service; Type: SCHEMA; Schema: -; Owner: product_service
--

CREATE SCHEMA product_service;


ALTER SCHEMA product_service OWNER TO product_service;

--
-- Name: settlement_service; Type: SCHEMA; Schema: -; Owner: settlement_service
--

CREATE SCHEMA settlement_service;


ALTER SCHEMA settlement_service OWNER TO settlement_service;

--
-- Name: user_service; Type: SCHEMA; Schema: -; Owner: user_service
--

CREATE SCHEMA user_service;


ALTER SCHEMA user_service OWNER TO user_service;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: auth; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.auth (
    id uuid NOT NULL,
    connected_at timestamp(6) with time zone NOT NULL,
    oauth_id character varying(100) NOT NULL,
    provider character varying(20) NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT auth_provider_check CHECK (((provider)::text = ANY (ARRAY[('KAKAO'::character varying)::text, ('NAVER'::character varying)::text, ('GOOGLE'::character varying)::text])))
);


ALTER TABLE public.auth OWNER TO prompthub;

--
-- Name: batch_job_execution; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.batch_job_execution (
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


ALTER TABLE public.batch_job_execution OWNER TO prompthub;

--
-- Name: batch_job_execution_context; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.batch_job_execution_context (
    job_execution_id bigint NOT NULL,
    short_context character varying(2500) NOT NULL,
    serialized_context text
);


ALTER TABLE public.batch_job_execution_context OWNER TO prompthub;

--
-- Name: batch_job_execution_params; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.batch_job_execution_params (
    job_execution_id bigint NOT NULL,
    parameter_name character varying(100) NOT NULL,
    parameter_type character varying(100) NOT NULL,
    parameter_value character varying(2500),
    identifying character(1) NOT NULL
);


ALTER TABLE public.batch_job_execution_params OWNER TO prompthub;

--
-- Name: batch_job_execution_seq; Type: SEQUENCE; Schema: public; Owner: prompthub
--

CREATE SEQUENCE public.batch_job_execution_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.batch_job_execution_seq OWNER TO prompthub;

--
-- Name: batch_job_instance; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.batch_job_instance (
    job_instance_id bigint NOT NULL,
    version bigint,
    job_name character varying(100) NOT NULL,
    job_key character varying(32) NOT NULL
);


ALTER TABLE public.batch_job_instance OWNER TO prompthub;

--
-- Name: batch_job_instance_seq; Type: SEQUENCE; Schema: public; Owner: prompthub
--

CREATE SEQUENCE public.batch_job_instance_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.batch_job_instance_seq OWNER TO prompthub;

--
-- Name: batch_step_execution; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.batch_step_execution (
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


ALTER TABLE public.batch_step_execution OWNER TO prompthub;

--
-- Name: batch_step_execution_context; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.batch_step_execution_context (
    step_execution_id bigint NOT NULL,
    short_context character varying(2500) NOT NULL,
    serialized_context text
);


ALTER TABLE public.batch_step_execution_context OWNER TO prompthub;

--
-- Name: batch_step_execution_seq; Type: SEQUENCE; Schema: public; Owner: prompthub
--

CREATE SEQUENCE public.batch_step_execution_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.batch_step_execution_seq OWNER TO prompthub;

--
-- Name: cart; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.cart (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    buyer_id uuid NOT NULL,
    total_amount integer NOT NULL
);


ALTER TABLE public.cart OWNER TO prompthub;

--
-- Name: cart_product; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.cart_product (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    added_at timestamp(6) without time zone NOT NULL,
    product_id uuid NOT NULL,
    cart_id uuid NOT NULL
);


ALTER TABLE public.cart_product OWNER TO prompthub;

--
-- Name: order; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public."order" (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    buyer_id uuid NOT NULL,
    canceled_at timestamp(6) without time zone,
    order_number character varying(30) NOT NULL,
    order_status character varying(20) NOT NULL,
    paid_at timestamp(6) without time zone,
    refunded_at timestamp(6) without time zone,
    total_order_amount integer NOT NULL,
    total_product_count integer NOT NULL,
    CONSTRAINT order_order_status_check CHECK (((order_status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PAID'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELED'::character varying)::text, ('REFUNDED'::character varying)::text])))
);


ALTER TABLE public."order" OWNER TO prompthub;

--
-- Name: order_outbox_event; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.order_outbox_event (
    event_id uuid NOT NULL,
    order_id uuid NOT NULL,
    event_type character varying(100) NOT NULL,
    payload text NOT NULL,
    status character varying(20) NOT NULL,
    retry_count integer NOT NULL,
    occurred_at timestamp(6) without time zone NOT NULL,
    published_at timestamp(6) without time zone,
    CONSTRAINT order_outbox_event_status_check CHECK (((status)::text = ANY (ARRAY['PENDING'::text, 'PUBLISHED'::text, 'FAILED'::text])))
);


ALTER TABLE public.order_outbox_event OWNER TO prompthub;

--
-- Name: order_payment; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.order_payment (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    approved_amount integer NOT NULL,
    approved_at timestamp(6) without time zone NOT NULL,
    buyer_id uuid NOT NULL,
    order_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    payment_method character varying(30) NOT NULL,
    pg_tx_id character varying(100) NOT NULL,
    provider character varying(50) NOT NULL
);


ALTER TABLE public.order_payment OWNER TO prompthub;

--
-- Name: order_processed_event; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.order_processed_event (
    id bigint NOT NULL,
    event_id uuid NOT NULL,
    consumer_group character varying(100) NOT NULL,
    event_type character varying(100) NOT NULL,
    event_occurred_at timestamp(6) without time zone NOT NULL,
    processed_at timestamp(6) without time zone NOT NULL
);


ALTER TABLE public.order_processed_event OWNER TO prompthub;

--
-- Name: order_processed_event_id_seq; Type: SEQUENCE; Schema: public; Owner: prompthub
--

ALTER TABLE public.order_processed_event ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.order_processed_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: order_product; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.order_product (
    id uuid NOT NULL,
    canceled_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone NOT NULL,
    downloaded boolean NOT NULL,
    order_product_status character varying(20) NOT NULL,
    product_amount_snapshot integer NOT NULL,
    product_id uuid NOT NULL,
    product_model_snapshot character varying(50),
    product_title_snapshot character varying(200) NOT NULL,
    product_type_snapshot character varying(30) NOT NULL,
    refunded_at timestamp(6) without time zone,
    seller_id uuid NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    order_id uuid NOT NULL,
    CONSTRAINT order_product_order_product_status_check CHECK (((order_product_status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PAID'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELED'::character varying)::text, ('REFUNDED'::character varying)::text])))
);


ALTER TABLE public.order_product OWNER TO prompthub;

--
-- Name: order_refund; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.order_refund (
    manual_review_required boolean NOT NULL,
    reconciliation_attempt integer NOT NULL,
    total_refund_amount integer NOT NULL,
    completed_at timestamp(6) without time zone,
    failed_at timestamp(6) without time zone,
    next_check_at timestamp(6) without time zone,
    requested_at timestamp(6) without time zone NOT NULL,
    timeout_at timestamp(6) without time zone,
    buyer_id uuid NOT NULL,
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    failure_code character varying(100),
    reason character varying(500),
    failure_reason character varying(1000),
    CONSTRAINT order_refund_status_check CHECK (((status)::text = ANY (ARRAY[('REQUESTED'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('TIMEOUT'::character varying)::text])))
);


ALTER TABLE public.order_refund OWNER TO prompthub;

--
-- Name: order_refund_product; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.order_refund_product (
    refund_amount integer NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    id uuid NOT NULL,
    order_product_id uuid NOT NULL,
    order_refund_id uuid NOT NULL
);


ALTER TABLE public.order_refund_product OWNER TO prompthub;

--
-- Name: order_snapshot; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.order_snapshot (
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


ALTER TABLE public.order_snapshot OWNER TO prompthub;

--
-- Name: payment; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.payment (
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


ALTER TABLE public.payment OWNER TO prompthub;

--
-- Name: product; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.product (
    id uuid NOT NULL,
    amount integer NOT NULL,
    amount_type character varying(255) NOT NULL,
    badge character varying(50),
    change_reason character varying(500),
    content text,
    created_at timestamp(6) without time zone NOT NULL,
    deleted_at timestamp(6) without time zone,
    description text NOT NULL,
    major_version smallint NOT NULL,
    model character varying(100),
    name character varying(200) NOT NULL,
    parent_id uuid,
    patch_version smallint NOT NULL,
    product_type character varying(50) NOT NULL,
    rejection_reason character varying(1000),
    sales_count integer NOT NULL,
    seller_id uuid NOT NULL,
    status character varying(255) NOT NULL,
    tags character varying(255),
    thumbnail_url character varying(500),
    updated_at timestamp(6) without time zone NOT NULL,
    view_count integer NOT NULL,
    wish_count integer NOT NULL,
    image_urls text,
    CONSTRAINT product_amount_type_check CHECK (((amount_type)::text = ANY (ARRAY[('FREE'::character varying)::text, ('PAID'::character varying)::text]))),
    CONSTRAINT product_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PENDING_REVIEW'::character varying)::text, ('ON_SALE'::character varying)::text, ('REJECTED'::character varying)::text, ('STOPPED'::character varying)::text, ('SUPERSEDED'::character varying)::text])))
);


ALTER TABLE public.product OWNER TO prompthub;

--
-- Name: product_processed_event; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.product_processed_event (
    id bigint NOT NULL,
    event_id uuid NOT NULL,
    consumer_group character varying(100) NOT NULL,
    event_type character varying(100) NOT NULL,
    event_occurred_at timestamp(6) without time zone,
    processed_at timestamp(6) without time zone NOT NULL
);


ALTER TABLE public.product_processed_event OWNER TO prompthub;

--
-- Name: product_processed_event_id_seq; Type: SEQUENCE; Schema: public; Owner: prompthub
--

ALTER TABLE public.product_processed_event ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.product_processed_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: refresh_token; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.refresh_token (
    id uuid NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    token text NOT NULL,
    user_id uuid NOT NULL
);


ALTER TABLE public.refresh_token OWNER TO prompthub;

--
-- Name: refund; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.refund (
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


ALTER TABLE public.refund OWNER TO prompthub;

--
-- Name: review; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.review (
    id uuid NOT NULL,
    content character varying(255),
    created_at timestamp(6) without time zone NOT NULL,
    deleted_at timestamp(6) without time zone,
    rating smallint NOT NULL,
    status character varying(255) NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    user_id uuid NOT NULL,
    product_id uuid,
    CONSTRAINT review_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('HIDDEN'::character varying)::text])))
);


ALTER TABLE public.review OWNER TO prompthub;

--
-- Name: seller_register; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.seller_register (
    id uuid NOT NULL,
    agreed_to_terms boolean NOT NULL,
    introduction text,
    portfolio_url character varying(500),
    reject_reason text,
    reviewed_at timestamp(6) without time zone,
    status character varying(20) NOT NULL,
    submitted_at timestamp(6) without time zone NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT seller_register_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


ALTER TABLE public.seller_register OWNER TO prompthub;

--
-- Name: seller_register_category; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.seller_register_category (
    seller_register_id uuid NOT NULL,
    category character varying(100) NOT NULL
);


ALTER TABLE public.seller_register_category OWNER TO prompthub;

--
-- Name: seller_settlement; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.seller_settlement (
    seller_settlement_id uuid NOT NULL,
    settlement_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    product_count integer NOT NULL,
    total_amount numeric(12,2) NOT NULL,
    settlement_total_amount numeric(12,2) NOT NULL,
    fee_total_amount numeric(12,2) NOT NULL,
    refund_amount numeric(12,2),
    calculated_at timestamp(6) without time zone NOT NULL,
    status character varying(30) NOT NULL,
    approved_at timestamp(6) without time zone,
    payout_requested_at timestamp(6) without time zone,
    paid_at timestamp(6) without time zone,
    cancelled_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT seller_settlement_status_check CHECK (((status)::text = ANY (ARRAY['WAITING'::text, 'APPROVAL_ON_HOLD'::text, 'APPROVED'::text, 'PAYOUT_REQUESTED'::text, 'PAYOUT_ON_HOLD'::text, 'PAID'::text, 'CANCELLED'::text])))
);


ALTER TABLE public.seller_settlement OWNER TO prompthub;

--
-- Name: settlement; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.settlement (
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


ALTER TABLE public.settlement OWNER TO prompthub;

--
-- Name: settlement_batch; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.settlement_batch (
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


ALTER TABLE public.settlement_batch OWNER TO prompthub;

--
-- Name: settlement_detail; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.settlement_detail (
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


ALTER TABLE public.settlement_detail OWNER TO prompthub;

--
-- Name: settlement_outbox_event; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.settlement_outbox_event (
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


ALTER TABLE public.settlement_outbox_event OWNER TO prompthub;

--
-- Name: settlement_source_line; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.settlement_source_line (
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


ALTER TABLE public.settlement_source_line OWNER TO prompthub;

--
-- Name: user; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public."user" (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    email character varying(255) NOT NULL,
    name character varying(100) NOT NULL,
    profile_image_url character varying(500),
    status character varying(20) NOT NULL,
    terms_agreed boolean NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT user_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('BLOCKED'::character varying)::text, ('WITHDRAWN'::character varying)::text])))
);


ALTER TABLE public."user" OWNER TO prompthub;

--
-- Name: user_role; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.user_role (
    user_id uuid NOT NULL,
    role character varying(20) NOT NULL,
    CONSTRAINT user_role_role_check CHECK (((role)::text = ANY (ARRAY[('BUYER'::character varying)::text, ('SELLER'::character varying)::text, ('ADMIN'::character varying)::text])))
);


ALTER TABLE public.user_role OWNER TO prompthub;

--
-- Name: wishlist; Type: TABLE; Schema: public; Owner: prompthub
--

CREATE TABLE public.wishlist (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    product_id uuid NOT NULL,
    user_id uuid NOT NULL
);


ALTER TABLE public.wishlist OWNER TO prompthub;

--
-- Name: auth auth_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.auth
    ADD CONSTRAINT auth_pkey PRIMARY KEY (id);


--
-- Name: batch_job_execution_context batch_job_execution_context_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.batch_job_execution_context
    ADD CONSTRAINT batch_job_execution_context_pkey PRIMARY KEY (job_execution_id);


--
-- Name: batch_job_execution batch_job_execution_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.batch_job_execution
    ADD CONSTRAINT batch_job_execution_pkey PRIMARY KEY (job_execution_id);


--
-- Name: batch_job_instance batch_job_instance_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.batch_job_instance
    ADD CONSTRAINT batch_job_instance_pkey PRIMARY KEY (job_instance_id);


--
-- Name: batch_step_execution_context batch_step_execution_context_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.batch_step_execution_context
    ADD CONSTRAINT batch_step_execution_context_pkey PRIMARY KEY (step_execution_id);


--
-- Name: batch_step_execution batch_step_execution_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.batch_step_execution
    ADD CONSTRAINT batch_step_execution_pkey PRIMARY KEY (step_execution_id);


--
-- Name: cart cart_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.cart
    ADD CONSTRAINT cart_pkey PRIMARY KEY (id);


--
-- Name: cart_product cart_product_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.cart_product
    ADD CONSTRAINT cart_product_pkey PRIMARY KEY (id);


--
-- Name: batch_job_instance job_inst_un; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.batch_job_instance
    ADD CONSTRAINT job_inst_un UNIQUE (job_name, job_key);


--
-- Name: order_outbox_event order_outbox_event_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_outbox_event
    ADD CONSTRAINT order_outbox_event_pkey PRIMARY KEY (event_id);


--
-- Name: order_payment order_payment_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_payment
    ADD CONSTRAINT order_payment_pkey PRIMARY KEY (id);


--
-- Name: order order_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public."order"
    ADD CONSTRAINT order_pkey PRIMARY KEY (id);


--
-- Name: order_processed_event order_processed_event_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_processed_event
    ADD CONSTRAINT order_processed_event_pkey PRIMARY KEY (id);


--
-- Name: order_product order_product_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_product
    ADD CONSTRAINT order_product_pkey PRIMARY KEY (id);


--
-- Name: order_refund order_refund_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_refund
    ADD CONSTRAINT order_refund_pkey PRIMARY KEY (id);


--
-- Name: order_refund_product order_refund_product_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_refund_product
    ADD CONSTRAINT order_refund_product_pkey PRIMARY KEY (id);


--
-- Name: order_snapshot order_snapshot_order_id_key; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_snapshot
    ADD CONSTRAINT order_snapshot_order_id_key UNIQUE (order_id);


--
-- Name: order_snapshot order_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_snapshot
    ADD CONSTRAINT order_snapshot_pkey PRIMARY KEY (id);


--
-- Name: payment payment_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.payment
    ADD CONSTRAINT payment_pkey PRIMARY KEY (id);


--
-- Name: product product_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.product
    ADD CONSTRAINT product_pkey PRIMARY KEY (id);


--
-- Name: product_processed_event product_processed_event_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.product_processed_event
    ADD CONSTRAINT product_processed_event_pkey PRIMARY KEY (id);


--
-- Name: refresh_token refresh_token_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT refresh_token_pkey PRIMARY KEY (id);


--
-- Name: refund refund_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.refund
    ADD CONSTRAINT refund_pkey PRIMARY KEY (id);


--
-- Name: review review_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.review
    ADD CONSTRAINT review_pkey PRIMARY KEY (id);


--
-- Name: seller_register seller_register_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.seller_register
    ADD CONSTRAINT seller_register_pkey PRIMARY KEY (id);


--
-- Name: seller_settlement seller_settlement_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.seller_settlement
    ADD CONSTRAINT seller_settlement_pkey PRIMARY KEY (seller_settlement_id);


--
-- Name: seller_settlement seller_settlement_settlement_id_key; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.seller_settlement
    ADD CONSTRAINT seller_settlement_settlement_id_key UNIQUE (settlement_id);


--
-- Name: settlement_batch settlement_batch_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.settlement_batch
    ADD CONSTRAINT settlement_batch_pkey PRIMARY KEY (batch_id);


--
-- Name: settlement_detail settlement_detail_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.settlement_detail
    ADD CONSTRAINT settlement_detail_pkey PRIMARY KEY (settlement_detail_id);


--
-- Name: settlement_outbox_event settlement_outbox_event_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.settlement_outbox_event
    ADD CONSTRAINT settlement_outbox_event_pkey PRIMARY KEY (event_id);


--
-- Name: settlement settlement_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.settlement
    ADD CONSTRAINT settlement_pkey PRIMARY KEY (settlement_id);


--
-- Name: settlement_source_line settlement_source_line_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.settlement_source_line
    ADD CONSTRAINT settlement_source_line_pkey PRIMARY KEY (settlement_source_line_id);


--
-- Name: settlement_batch uk49w5gmo3stsjq44xvybxn2fau; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.settlement_batch
    ADD CONSTRAINT uk49w5gmo3stsjq44xvybxn2fau UNIQUE (batch_no);


--
-- Name: order uk4b9qgonn9wr1584ye6j5acwga; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public."order"
    ADD CONSTRAINT uk4b9qgonn9wr1584ye6j5acwga UNIQUE (order_number);


--
-- Name: auth uk6rcbwyd3cg9b1rgfmyu5tdc43; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.auth
    ADD CONSTRAINT uk6rcbwyd3cg9b1rgfmyu5tdc43 UNIQUE (provider, oauth_id);


--
-- Name: settlement_source_line uk7un5j0j13x9w7yaqlu6g2omt8; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.settlement_source_line
    ADD CONSTRAINT uk7un5j0j13x9w7yaqlu6g2omt8 UNIQUE (event_id);


--
-- Name: order_payment uk_order_payment_order_id; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_payment
    ADD CONSTRAINT uk_order_payment_order_id UNIQUE (order_id);


--
-- Name: order_payment uk_order_payment_payment_id; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_payment
    ADD CONSTRAINT uk_order_payment_payment_id UNIQUE (payment_id);


--
-- Name: order_payment uk_order_payment_pg_tx_id; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_payment
    ADD CONSTRAINT uk_order_payment_pg_tx_id UNIQUE (pg_tx_id);


--
-- Name: order_processed_event uk_order_processed_event_id_group; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_processed_event
    ADD CONSTRAINT uk_order_processed_event_id_group UNIQUE (event_id, consumer_group);


--
-- Name: order_refund_product uk_order_refund_product_order_product; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_refund_product
    ADD CONSTRAINT uk_order_refund_product_order_product UNIQUE (order_product_id);


--
-- Name: order_refund_product uk_order_refund_product_refund_product; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_refund_product
    ADD CONSTRAINT uk_order_refund_product_refund_product UNIQUE (order_refund_id, order_product_id);


--
-- Name: product_processed_event uk_product_processed_event_id_group; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.product_processed_event
    ADD CONSTRAINT uk_product_processed_event_id_group UNIQUE (event_id, consumer_group);


--
-- Name: user ukhl4ga9r00rh51mdaf20hmnslt; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT ukhl4ga9r00rh51mdaf20hmnslt UNIQUE (email);


--
-- Name: user user_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT user_pkey PRIMARY KEY (id);


--
-- Name: user_role user_role_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT user_role_pkey PRIMARY KEY (user_id, role);


--
-- Name: wishlist wishlist_pkey; Type: CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.wishlist
    ADD CONSTRAINT wishlist_pkey PRIMARY KEY (id);


--
-- Name: idx_order_outbox_event_order_id; Type: INDEX; Schema: public; Owner: prompthub
--

CREATE INDEX idx_order_outbox_event_order_id ON public.order_outbox_event USING btree (order_id);


--
-- Name: idx_order_outbox_event_status_occurred_at; Type: INDEX; Schema: public; Owner: prompthub
--

CREATE INDEX idx_order_outbox_event_status_occurred_at ON public.order_outbox_event USING btree (status, occurred_at);


--
-- Name: idx_order_processed_event_event_type; Type: INDEX; Schema: public; Owner: prompthub
--

CREATE INDEX idx_order_processed_event_event_type ON public.order_processed_event USING btree (event_type);


--
-- Name: idx_order_processed_event_processed_at; Type: INDEX; Schema: public; Owner: prompthub
--

CREATE INDEX idx_order_processed_event_processed_at ON public.order_processed_event USING btree (processed_at);


--
-- Name: idx_product_processed_event_processed_at; Type: INDEX; Schema: public; Owner: prompthub
--

CREATE INDEX idx_product_processed_event_processed_at ON public.product_processed_event USING btree (processed_at);


--
-- Name: idx_settlement_outbox_aggregate_id; Type: INDEX; Schema: public; Owner: prompthub
--

CREATE INDEX idx_settlement_outbox_aggregate_id ON public.settlement_outbox_event USING btree (aggregate_id);


--
-- Name: idx_settlement_outbox_batch_status_occurred; Type: INDEX; Schema: public; Owner: prompthub
--

CREATE INDEX idx_settlement_outbox_batch_status_occurred ON public.settlement_outbox_event USING btree (settlement_batch_id, status, occurred_at, event_id);


--
-- Name: idx_settlement_outbox_status_attempted_occurred; Type: INDEX; Schema: public; Owner: prompthub
--

CREATE INDEX idx_settlement_outbox_status_attempted_occurred ON public.settlement_outbox_event USING btree (status, last_attempted_at, occurred_at, event_id);


--
-- Name: uk_payment_order_paid; Type: INDEX; Schema: public; Owner: prompthub
--

CREATE UNIQUE INDEX uk_payment_order_paid ON public.payment USING btree (order_id) WHERE ((status)::text = 'PAID'::text);


--
-- Name: uk_payment_pg_tx_id; Type: INDEX; Schema: public; Owner: prompthub
--

CREATE UNIQUE INDEX uk_payment_pg_tx_id ON public.payment USING btree (pg_tx_id);


--
-- Name: order_refund_product fk4d2iveqpwu3di6r301c7gs1ov; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_refund_product
    ADD CONSTRAINT fk4d2iveqpwu3di6r301c7gs1ov FOREIGN KEY (order_product_id) REFERENCES public.order_product(id);


--
-- Name: settlement_detail fk6v53alv8ajhplgsd464u4o9sk; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.settlement_detail
    ADD CONSTRAINT fk6v53alv8ajhplgsd464u4o9sk FOREIGN KEY (settlement_id) REFERENCES public.settlement(settlement_id);


--
-- Name: seller_register_category fk8jt8sdongt6i30jim4ivfd0lm; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.seller_register_category
    ADD CONSTRAINT fk8jt8sdongt6i30jim4ivfd0lm FOREIGN KEY (seller_register_id) REFERENCES public.seller_register(id);


--
-- Name: order_refund fkc1c1crcr60t3evucmr4yk18x4; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_refund
    ADD CONSTRAINT fkc1c1crcr60t3evucmr4yk18x4 FOREIGN KEY (order_id) REFERENCES public."order"(id);


--
-- Name: user_role fkfgsgxvihks805qcq8sq26ab7c; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT fkfgsgxvihks805qcq8sq26ab7c FOREIGN KEY (user_id) REFERENCES public."user"(id);


--
-- Name: review fkiyof1sindb9qiqr9o8npj8klt; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.review
    ADD CONSTRAINT fkiyof1sindb9qiqr9o8npj8klt FOREIGN KEY (product_id) REFERENCES public.product(id);


--
-- Name: order_refund_product fkklcrmch3sdmcddkb4cjnjcfui; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_refund_product
    ADD CONSTRAINT fkklcrmch3sdmcddkb4cjnjcfui FOREIGN KEY (order_refund_id) REFERENCES public.order_refund(id);


--
-- Name: cart_product fklv5x4iresnv4xspvomrwd8ej9; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.cart_product
    ADD CONSTRAINT fklv5x4iresnv4xspvomrwd8ej9 FOREIGN KEY (cart_id) REFERENCES public.cart(id);


--
-- Name: order_product fkq5kokaug6qg2dtttgdw7kiris; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.order_product
    ADD CONSTRAINT fkq5kokaug6qg2dtttgdw7kiris FOREIGN KEY (order_id) REFERENCES public."order"(id);


--
-- Name: batch_job_execution_context job_exec_ctx_fk; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.batch_job_execution_context
    ADD CONSTRAINT job_exec_ctx_fk FOREIGN KEY (job_execution_id) REFERENCES public.batch_job_execution(job_execution_id);


--
-- Name: batch_job_execution_params job_exec_params_fk; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.batch_job_execution_params
    ADD CONSTRAINT job_exec_params_fk FOREIGN KEY (job_execution_id) REFERENCES public.batch_job_execution(job_execution_id);


--
-- Name: batch_step_execution job_exec_step_fk; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.batch_step_execution
    ADD CONSTRAINT job_exec_step_fk FOREIGN KEY (job_execution_id) REFERENCES public.batch_job_execution(job_execution_id);


--
-- Name: batch_job_execution job_inst_exec_fk; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.batch_job_execution
    ADD CONSTRAINT job_inst_exec_fk FOREIGN KEY (job_instance_id) REFERENCES public.batch_job_instance(job_instance_id);


--
-- Name: batch_step_execution_context step_exec_ctx_fk; Type: FK CONSTRAINT; Schema: public; Owner: prompthub
--

ALTER TABLE ONLY public.batch_step_execution_context
    ADD CONSTRAINT step_exec_ctx_fk FOREIGN KEY (step_execution_id) REFERENCES public.batch_step_execution(step_execution_id);


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: order_service; Owner: prompthub
--

ALTER DEFAULT PRIVILEGES FOR ROLE prompthub IN SCHEMA order_service GRANT ALL ON SEQUENCES TO order_service;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: order_service; Owner: prompthub
--

ALTER DEFAULT PRIVILEGES FOR ROLE prompthub IN SCHEMA order_service GRANT ALL ON TABLES TO order_service;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: payment_service; Owner: prompthub
--

ALTER DEFAULT PRIVILEGES FOR ROLE prompthub IN SCHEMA payment_service GRANT ALL ON SEQUENCES TO payment_service;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: payment_service; Owner: prompthub
--

ALTER DEFAULT PRIVILEGES FOR ROLE prompthub IN SCHEMA payment_service GRANT ALL ON TABLES TO payment_service;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: product_service; Owner: prompthub
--

ALTER DEFAULT PRIVILEGES FOR ROLE prompthub IN SCHEMA product_service GRANT ALL ON SEQUENCES TO product_service;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: product_service; Owner: prompthub
--

ALTER DEFAULT PRIVILEGES FOR ROLE prompthub IN SCHEMA product_service GRANT ALL ON TABLES TO product_service;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: settlement_service; Owner: prompthub
--

ALTER DEFAULT PRIVILEGES FOR ROLE prompthub IN SCHEMA settlement_service GRANT ALL ON SEQUENCES TO settlement_service;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: settlement_service; Owner: prompthub
--

ALTER DEFAULT PRIVILEGES FOR ROLE prompthub IN SCHEMA settlement_service GRANT ALL ON TABLES TO settlement_service;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: user_service; Owner: prompthub
--

ALTER DEFAULT PRIVILEGES FOR ROLE prompthub IN SCHEMA user_service GRANT ALL ON SEQUENCES TO user_service;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: user_service; Owner: prompthub
--

ALTER DEFAULT PRIVILEGES FOR ROLE prompthub IN SCHEMA user_service GRANT ALL ON TABLES TO user_service;


--
-- PostgreSQL database dump complete
--


