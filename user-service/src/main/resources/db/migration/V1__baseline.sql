--
-- user-service baseline schema
--
-- 이 서비스가 실제로 소유하는 테이블만 정의한다. 스키마 접두사를 명시하지 않고
-- JDBC URL의 currentSchema(user_service)에 위임한다.
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

CREATE TABLE auth (
    id uuid NOT NULL,
    connected_at timestamp(6) with time zone NOT NULL,
    oauth_id character varying(100) NOT NULL,
    provider character varying(20) NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT auth_provider_check CHECK (((provider)::text = ANY (ARRAY[('KAKAO'::character varying)::text, ('NAVER'::character varying)::text, ('GOOGLE'::character varying)::text])))
);

CREATE TABLE refresh_token (
    id uuid NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    token text NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE seller_register (
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

CREATE TABLE seller_register_category (
    seller_register_id uuid NOT NULL,
    category character varying(100) NOT NULL
);

CREATE TABLE seller_settlement (
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

CREATE TABLE "user" (
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

CREATE TABLE user_role (
    user_id uuid NOT NULL,
    role character varying(20) NOT NULL,
    CONSTRAINT user_role_role_check CHECK (((role)::text = ANY (ARRAY[('BUYER'::character varying)::text, ('SELLER'::character varying)::text, ('ADMIN'::character varying)::text])))
);

CREATE TABLE wishlist (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    product_id uuid NOT NULL,
    user_id uuid NOT NULL
);

ALTER TABLE ONLY auth
    ADD CONSTRAINT auth_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refresh_token
    ADD CONSTRAINT refresh_token_pkey PRIMARY KEY (id);

ALTER TABLE ONLY seller_register
    ADD CONSTRAINT seller_register_pkey PRIMARY KEY (id);

ALTER TABLE ONLY seller_settlement
    ADD CONSTRAINT seller_settlement_pkey PRIMARY KEY (seller_settlement_id);

ALTER TABLE ONLY seller_settlement
    ADD CONSTRAINT seller_settlement_settlement_id_key UNIQUE (settlement_id);

ALTER TABLE ONLY "user"
    ADD CONSTRAINT user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY "user"
    ADD CONSTRAINT ukhl4ga9r00rh51mdaf20hmnslt UNIQUE (email);

ALTER TABLE ONLY user_role
    ADD CONSTRAINT user_role_pkey PRIMARY KEY (user_id, role);

ALTER TABLE ONLY wishlist
    ADD CONSTRAINT wishlist_pkey PRIMARY KEY (id);

ALTER TABLE ONLY auth
    ADD CONSTRAINT uk6rcbwyd3cg9b1rgfmyu5tdc43 UNIQUE (provider, oauth_id);

ALTER TABLE ONLY seller_register_category
    ADD CONSTRAINT fk8jt8sdongt6i30jim4ivfd0lm FOREIGN KEY (seller_register_id) REFERENCES seller_register(id);

ALTER TABLE ONLY user_role
    ADD CONSTRAINT fkfgsgxvihks805qcq8sq26ab7c FOREIGN KEY (user_id) REFERENCES "user"(id);
