--
-- product-service baseline schema
--
-- 이 서비스가 실제로 소유하는 테이블만 정의한다. 스키마 접두사를 명시하지 않고
-- JDBC URL의 currentSchema(product_service)에 위임한다.
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

CREATE TABLE product (
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

CREATE TABLE review (
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

CREATE TABLE product_processed_event (
    id bigint NOT NULL,
    event_id uuid NOT NULL,
    consumer_group character varying(100) NOT NULL,
    event_type character varying(100) NOT NULL,
    event_occurred_at timestamp(6) without time zone,
    processed_at timestamp(6) without time zone NOT NULL
);

ALTER TABLE product_processed_event ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME product_processed_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY product
    ADD CONSTRAINT product_pkey PRIMARY KEY (id);

ALTER TABLE ONLY review
    ADD CONSTRAINT review_pkey PRIMARY KEY (id);

ALTER TABLE ONLY product_processed_event
    ADD CONSTRAINT product_processed_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY product_processed_event
    ADD CONSTRAINT uk_product_processed_event_id_group UNIQUE (event_id, consumer_group);

CREATE INDEX idx_product_processed_event_processed_at ON product_processed_event USING btree (processed_at);

ALTER TABLE ONLY review
    ADD CONSTRAINT fkiyof1sindb9qiqr9o8npj8klt FOREIGN KEY (product_id) REFERENCES product(id);
