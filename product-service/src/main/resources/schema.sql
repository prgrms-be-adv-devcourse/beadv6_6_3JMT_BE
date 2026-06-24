-- =============================================
-- Product Service Schema
-- API 명세서 기준
-- =============================================

-- category
DROP TABLE IF EXISTS category CASCADE;

CREATE TABLE category
(
    id            UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    parent_id     UUID                  REFERENCES category (id),
    code          VARCHAR(50)  NOT NULL UNIQUE,
    name          VARCHAR(100) NOT NULL,
    icon          VARCHAR(50),
    display_order INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- review (product 참조하므로 product 먼저 DROP)
DROP TABLE IF EXISTS review CASCADE;

-- product
DROP TABLE IF EXISTS product CASCADE;

CREATE TABLE product
(
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    seller_id        UUID         NOT NULL,
    category_id      UUID                  REFERENCES category (id),
    major_version    SMALLINT     NOT NULL DEFAULT 1,
    patch_version    SMALLINT     NOT NULL DEFAULT 0,
    change_reason    VARCHAR(500),
    name             VARCHAR(200) NOT NULL,
    description      TEXT         NOT NULL,
    product_type     VARCHAR(50)  NOT NULL,
    amount_type      VARCHAR(20)  NOT NULL DEFAULT 'PAID' CHECK (amount_type IN ('FREE', 'PAID')),
    amount           INTEGER      NOT NULL DEFAULT 0,
    thumbnail_url    VARCHAR(500),
    content          TEXT,
    badge            VARCHAR(50),
    status           VARCHAR(30)  NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'ON_SALE', 'REJECTED', 'STOPPED')),
    rejection_reason VARCHAR(1000),
    sales_count      INTEGER      NOT NULL DEFAULT 0,
    view_count       INTEGER      NOT NULL DEFAULT 0,
    wish_count       INTEGER      NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at       TIMESTAMP
);

-- review
CREATE TABLE review
(
    id         UUID      NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id    UUID      NOT NULL,
    product_id UUID               REFERENCES product (id),
    rating     SMALLINT  NOT NULL CHECK (rating BETWEEN 1 AND 5),
    content    TEXT,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'HIDDEN')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP
);
