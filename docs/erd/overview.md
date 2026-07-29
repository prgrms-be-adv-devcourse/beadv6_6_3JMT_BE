# 전체 ERD 다이어그램

서비스 경계별로 색을 구분해 표기. 실선은 FK 참조, 점선은 논리적 연관(명시적 FK 제약 없음).

```mermaid
erDiagram
    %% ───────────────────────────────
    %% User Service
    %% ───────────────────────────────
    user {
        UUID user_id PK
        VARCHAR name
        VARCHAR email
        VARCHAR profile_image_url
        user_status_type status
        BOOLEAN terms_agreed
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    user_role {
        UUID user_id PK
        user_role_type role PK
    }
    auth {
        UUID auth_id PK
        UUID user_id FK
        auth_provider_type provider
        VARCHAR provider_user_id
        TIMESTAMPTZ connected_at
    }
    wishlist {
        UUID wishlist_id PK
        UUID user_id FK
        UUID product_id FK
        TIMESTAMPTZ created_at
    }

    %% ───────────────────────────────
    %% Product Service
    %% ───────────────────────────────
    %% seller_id는 별도 seller 테이블이 아니라 user.user_id를 그대로 담는다(User Service 참고).
    %% product_image 테이블은 없다 — product.image_urls 컬럼(쉼표 구분 텍스트)로 대체한다.
    product {
        UUID id PK
        UUID seller_id FK
        SMALLINT major_version
        SMALLINT patch_version
        VARCHAR product_type
        VARCHAR name
        VARCHAR status
        VARCHAR amount_type
        INT amount
        TEXT image_urls
        VARCHAR badge
        TEXT content
        TEXT tags
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
        TIMESTAMPTZ deleted_at
    }
    review {
        UUID review_id PK
        UUID user_id FK
        UUID product_id FK
        SMALLINT rating
        TEXT content
        review_status_type status
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
        TIMESTAMPTZ deleted_at
    }

    %% ───────────────────────────────
    %% Order Service
    %% ───────────────────────────────
    cart {
        UUID cart_id PK
        UUID buyer_id FK
        INT total_amount
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    cart_product {
        UUID cart_product_id PK
        UUID cart_id FK
        UUID product_id FK
        TIMESTAMPTZ added_at
    }
    order {
        UUID order_id PK
        UUID buyer_id FK
        VARCHAR order_number
        INT total_order_amount
        order_status_type order_status
        TIMESTAMPTZ completed_at
        TIMESTAMPTZ refunded_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    order_product {
        UUID order_product_id PK
        UUID order_id FK
        UUID buyer_id FK
        UUID product_id FK
        UUID seller_id FK
        VARCHAR product_title_snapshot
        INT product_amount_snapshot
        order_product_status_type order_product_status
        BOOLEAN downloaded
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    %% ───────────────────────────────
    %% Payment Service
    %% ───────────────────────────────
    payment {
        UUID payment_id PK
        UUID order_id FK
        UUID user_id FK
        VARCHAR payment_key
        payment_status_type status
        INT total_amount
        JSONB request_payload
        JSONB response_payload
        TIMESTAMPTZ approved_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    refund {
        UUID refund_id PK
        UUID payment_id FK
        UUID refund_request_id
        INT refund_amount
        refund_status_type status
        TIMESTAMPTZ requested_at
        TIMESTAMPTZ completed_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    %% ───────────────────────────────
    %% Settlement Service
    %% ───────────────────────────────
    %% seller_id는 별도 seller 테이블이 아니라 user.user_id를 그대로 담는다(User Service 참고).
    settlement_batch {
        UUID batch_id PK
        VARCHAR batch_no
        BIGINT job_instance_id
        DATE period_start
        DATE period_end
        settlement_batch_status_type status
        trigger_type_enum trigger_type
        TIMESTAMPTZ executed_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    settlement {
        UUID settlement_id PK
        UUID settlement_batch_id FK
        UUID seller_id FK
        DATE period_start
        DATE period_end
        NUMERIC total_amount
        NUMERIC settlement_total_amount
        NUMERIC fee_total_amount
        settlement_status_type settlement_status
        payout_status_type payout_status
        TIMESTAMPTZ calculated_at
        TIMESTAMPTZ paid_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    settlement_detail {
        UUID settlement_detail_id PK
        UUID settlement_id FK
        UUID order_product_id FK
        NUMERIC line_amount
        NUMERIC fee_rate
        NUMERIC fee_amount
        NUMERIC line_settlement_amount
        settlement_line_type line_type
        TIMESTAMPTZ occurred_at
        TIMESTAMPTZ created_at
    }
    settlement_source_line {
        UUID settlement_source_line_id PK
        UUID event_id
        settlement_source_line_type line_type
        UUID order_id
        UUID order_product_id FK
        UUID seller_id FK
        NUMERIC line_amount
        TIMESTAMPTZ occurred_at
        UUID settlement_id FK
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    %% ───────────────────────────────
    %% Relationships
    %% ───────────────────────────────
    user ||--o{ user_role : "user_id"
    user ||--o{ auth : "user_id"
    user ||--o{ wishlist : "user_id"
    user ||--o| cart : "buyer_id"
    user ||--o{ review : "user_id"
    user ||--o{ order : "buyer_id"
    user ||--o{ payment : "user_id"
    user ||--o{ product : "seller_id(=user_id)"
    user ||--o{ settlement : "seller_id(=user_id)"

    product ||--o{ wishlist : "product_id"
    product ||--o{ cart_product : "product_id"
    product ||--o{ review : "product_id"
    product ||--o{ order_product : "product_id"

    cart ||--o{ cart_product : "cart_id"

    order ||--o{ order_product : "order_id"
    order ||--o| payment : "order_id"

    payment ||--o{ refund : "payment_id"

    settlement_batch ||--o{ settlement : "settlement_batch_id"
    settlement ||--o{ settlement_detail : "settlement_id"
    order_product ||--o{ settlement_detail : "order_product_id"
    settlement ||--o{ settlement_source_line : "settlement_id"
    order_product ||--o{ settlement_source_line : "order_product_id"
```
