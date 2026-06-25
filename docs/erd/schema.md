# 스키마 레퍼런스

`init-script/postgres/schema.sql` 기준. 실제 DDL이 기준이며 이 문서는 열람용 미러다.

@docs/api-spec 문서가 완성된 이후에 협의 후 변경! 해당 문서는 변경하면 안됩니다.

---

## ENUM 타입

| 타입명 | 값 |
|-------|-----|
| `user_status_type` | ACTIVE / BLOCKED / WITHDRAWN |
| `user_role_type` | USER / SELLER / ADMIN |
| `seller_status_type` | PENDING / ACTIVE / SUSPENDED |
| `auth_provider_type` | KAKAO / NAVER / GOOGLE |
| `product_status_type` | DRAFT / PENDING_REVIEW / ON_SALE / REJECTED / STOPPED |
| `amount_type_enum` | FREE / PAID |
| `order_status_type` | PENDING / PAID / FAILED / CANCELED / REFUNDED |
| `order_product_status_type` | PENDING / PAID / FAILED / CANCELED / REFUNDED |
| `payment_status_type` | READY / REQUESTED / PAID / FAILED / REFUNDING / REFUNDED / UNKNOWN |
| `refund_status_type` | REQUESTED / COMPLETED / FAILED |
| `settlement_status_type` | PROCESSING / COMPLETED / FAILED / CANCELLED |
| `payout_status_type` | PENDING / PAID / FAILED |
| `trigger_type_enum` | SCHEDULED / MANUAL |
| `settlement_line_type` | SALE / REFUND / ADJUSTMENT |
| `review_status_type` | ACTIVE / HIDDEN |

---

## User Service

### user

| 컬럼                | 타입 | NOT NULL | 기본값 | 설명 |
|-------------------|------|:--------:|--------|------|
| id                | UUID | ✓ | | PK |
| name              | VARCHAR(100) | ✓ | | 사용자 이름 |
| email             | VARCHAR(255) | ✓ | | 이메일 |
| profile_image_url | VARCHAR(500) | | NULL | 프로필 이미지 URL |
| status            | user_status_type | ✓ | ACTIVE | ACTIVE / BLOCKED / WITHDRAWN |
| terms_agreed      | BOOLEAN | ✓ | FALSE | 서비스 이용약관 동의 여부 |
| role              | user_role_type | ✓ | | USER / SELLER / ADMIN |
| created_at        | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |
| updated_at        | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |

---

### auth

| 컬럼           | 타입 | NOT NULL | 기본값 | 설명 |
|--------------|------|:--------:|--------|------|
| id           | UUID | ✓ | gen_random_uuid() | PK |
| user_id      | UUID | ✓ | | FK → user.user_id |
| provider     | auth_provider_type | ✓ | | KAKAO / NAVER / GOOGLE |
| oauth_id     | VARCHAR(100) | ✓ | | 소셜 플랫폼 고유 ID. UNIQUE(provider, provider_user_id) |
| connected_at | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | 소셜 계정 연동 일시 |

---

### seller

| 컬럼              | 타입 | NOT NULL | 기본값 | 설명 |
|-----------------|------|:--------:|--------|------|
| id              | UUID | ✓ | gen_random_uuid() | PK |
| user_id         | UUID | ✓ | | FK → user.user_id |
| seller_name     | VARCHAR(100) | ✓ | | 상호명 또는 판매자 노출 이름 |
| business_number | VARCHAR(50) | | NULL | 사업자등록번호. 개인/해외 판매자 확장성을 위해 NULL 허용 |
| status          | seller_status_type | ✓ | PENDING | PENDING / ACTIVE / SUSPENDED |
| approved_at     | TIMESTAMPTZ | | NULL | 판매자 승인 일시. 미승인 시 NULL |
| created_at      | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |
| updated_at      | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |

---

### wishlist

| 컬럼         | 타입 | NOT NULL | 기본값 | 설명 |
|------------|------|:--------:|--------|------|
| id         | UUID | ✓ | gen_random_uuid() | PK |
| user_id    | UUID | ✓ | | FK → user.user_id |
| product_id | UUID | ✓ | | FK → product.product_id |
| created_at | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |

---

## Product Service

### category

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| category_id | UUID | ✓ | gen_random_uuid() | PK |
| parent_id | UUID | | NULL | 부모 카테고리 FK (자기 참조). 최상위는 NULL |
| code | VARCHAR(50) | ✓ | | 카테고리 코드. API 필터/응답 식별값. UNIQUE |
| name | VARCHAR(100) | ✓ | | 카테고리 표시명 (화면 노출용) |
| icon | VARCHAR(50) | | NULL | 아이콘 slug. FE ICON_MAP key (예: `pen-line`, `code-xml`) |
| display_order | INT | ✓ | 0 | 노출 순서 |
| created_at | TIMESTAMPTZ | ✓ | | |
| updated_at | TIMESTAMPTZ | ✓ | | |

---

### product

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| product_id | UUID | ✓ | gen_random_uuid() | PK |
| seller_id | UUID | ✓ | | FK → seller.seller_id |
| category_id | UUID | | NULL | FK → category.category_id |
| parent_id | UUID | | NULL | 버전 계열 최초 원본 ID (자기 참조). 최초 등록은 NULL |
| major_version | SMALLINT | ✓ | 1 | 메이저 버전. MAJOR 선택 시 +1, patch_version 0 리셋 |
| patch_version | SMALLINT | ✓ | 0 | 패치 버전. PATCH 선택 시 +1. 표기: major.patch |
| change_reason | VARCHAR(500) | | NULL | 버전업 변경 사유 |
| name | VARCHAR(200) | ✓ | | 상품명 |
| description | TEXT | ✓ | | 상품 상세 설명 |
| product_type | VARCHAR(50) | ✓ | | PROMPT 등 |
| amount_type | amount_type_enum | ✓ | PAID | FREE / PAID |
| amount | INT | ✓ | 0 | 판매 가격 |
| thumbnail_url | VARCHAR(500) | | NULL | 대표 이미지 URL. NULL이면 기본 이미지 |
| content | TEXT | | NULL | 프롬프트/템플릿 원문. **외부 응답 노출 금지** |
| status | product_status_type | ✓ | DRAFT | DRAFT / PENDING_REVIEW / ON_SALE / REJECTED / STOPPED |
| rejection_reason | VARCHAR(1000) | | NULL | 검수 반려 사유. REJECTED 상태에서만 유효 |
| sales_count | INT | ✓ | 0 | 누적 판매 수 |
| view_count | INT | ✓ | 0 | 조회 수 |
| wish_count | INT | ✓ | 0 | 찜 수 |
| created_at | TIMESTAMPTZ | ✓ | | |
| updated_at | TIMESTAMPTZ | ✓ | | |
| deleted_at | TIMESTAMPTZ | | NULL | 소프트 삭제 일시 |

---

### product_image

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| image_id | UUID | ✓ | gen_random_uuid() | PK |
| product_id | UUID | ✓ | | FK → product.product_id. 상품 삭제 시 CASCADE |
| image_url | VARCHAR(500) | ✓ | | 이미지 URL |
| sort_order | INT | ✓ | 0 | 노출 순서 |
| created_at | TIMESTAMPTZ | ✓ | | |

---

### review

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| review_id | UUID | ✓ | gen_random_uuid() | PK |
| user_id | UUID | ✓ | | FK → user.user_id |
| product_id | UUID | | NULL | FK → product.product_id |
| rating | SMALLINT | ✓ | | 별점 1~5. CHECK(rating BETWEEN 1 AND 5) |
| content | TEXT | | NULL | 리뷰 내용 |
| status | review_status_type | ✓ | | ACTIVE / HIDDEN (관리자 숨김) |
| created_at | TIMESTAMPTZ | ✓ | | |
| updated_at | TIMESTAMPTZ | ✓ | | |
| deleted_at | TIMESTAMPTZ | | NULL | 소프트 삭제 일시 |

---

## Order Service

### cart

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| cart_id | UUID | ✓ | gen_random_uuid() | PK |
| buyer_id | UUID | ✓ | | FK → user.user_id |
| total_amount | INT | ✓ | 0 | 장바구니 총금액 |
| created_at | TIMESTAMPTZ | ✓ | | |
| updated_at | TIMESTAMPTZ | ✓ | | |

---

### cart_product

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| cart_product_id | UUID | ✓ | gen_random_uuid() | PK |
| cart_id | UUID | ✓ | | FK → cart.cart_id |
| product_id | UUID | ✓ | | FK → product.product_id |
| added_at | TIMESTAMPTZ | ✓ | | 장바구니에 담은 시각 |

---

### order

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| order_id | UUID | ✓ | gen_random_uuid() | PK |
| buyer_id | UUID | ✓ | | FK → user.user_id |
| order_number | VARCHAR(30) | ✓ | | 사용자 노출 주문 번호. UNIQUE |
| total_order_amount | INT | ✓ | 0 | 총 주문 금액 |
| total_product_count | INT | ✓ | 0 | 총 상품 수 |
| order_status | order_status_type | ✓ | PENDING | PENDING / PAID / FAILED / CANCELED / REFUNDED |
| created_at | TIMESTAMPTZ | ✓ | | 불변 |
| paid_at | TIMESTAMPTZ | | NULL | |
| canceled_at | TIMESTAMPTZ | | NULL | |
| refunded_at | TIMESTAMPTZ | | NULL | 부분환불용 확장 예정 |
| updated_at | TIMESTAMPTZ | ✓ | | |

---

### order_product

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| order_product_id | UUID | ✓ | gen_random_uuid() | PK |
| order_id | UUID | ✓ | | FK → order.order_id |
| product_id | UUID | ✓ | | FK → product.product_id |
| seller_id | UUID | ✓ | | FK → seller.seller_id. 정산 기준 |
| product_title_snapshot | VARCHAR(200) | ✓ | | 구매 당시 상품명 스냅샷 |
| product_type_snapshot | VARCHAR(30) | ✓ | | 구매 당시 상품 유형 스냅샷 |
| product_amount_snapshot | INT | ✓ | | 구매 당시 가격 스냅샷 |
| order_product_status | order_product_status_type | ✓ | | PENDING / PAID / FAILED / CANCELED / REFUNDED |
| is_download | BOOLEAN | ✓ | FALSE | 다운로드 여부 |
| created_at | TIMESTAMPTZ | ✓ | | 불변 |
| canceled_at | TIMESTAMPTZ | | NULL | 부분 취소 확장용 |
| refunded_at | TIMESTAMPTZ | | NULL | 부분 환불 확장용 |
| updated_at | TIMESTAMPTZ | ✓ | | |

---

## Payment Service

### payment

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| payment_id | UUID | ✓ | gen_random_uuid() | PK |
| order_id | UUID | ✓ | | FK → order.order_id |
| user_id | UUID | ✓ | | FK → user.user_id |
| pg_tx_id | VARCHAR(100) | ✓ | | 토스페이먼츠 paymentKey. 중복 처리 방지 기준값 |
| status | payment_status_type | ✓ | READY | |
| payment_method | VARCHAR(30) | ✓ | | CARD 등 |
| provider | VARCHAR(30) | ✓ | | TOSS_PAYMENTS 등 |
| is_test | BOOLEAN | ✓ | FALSE | 테스트 결제 여부 |
| total_amount | INT | ✓ | | 최종 결제 요청 금액 (product_amount - discount_amount) |
| product_amount | INT | ✓ | | 상품 원금액 |
| discount_amount | INT | ✓ | 0 | 할인 금액 |
| approved_amount | INT | | NULL | PG사 실제 승인 금액. 승인 전 NULL |
| idempotency_key | VARCHAR(255) | ✓ | | 중복 결제 방지 키. 형식: pay-{order_id}. UNIQUE |
| failure_code | VARCHAR(100) | | NULL | PG사 결제 실패 코드 |
| failure_reason | TEXT | | NULL | PG사 결제 실패 상세 사유 |
| request_payload | JSONB | | NULL | PG사 결제 요청 원문. 분쟁·디버깅용 |
| response_payload | JSONB | | NULL | PG사 응답 원문. 분쟁·디버깅용 |
| requested_at | TIMESTAMPTZ | | NULL | |
| approved_at | TIMESTAMPTZ | | NULL | |
| failed_at | TIMESTAMPTZ | | NULL | |
| refunded_at | TIMESTAMPTZ | | NULL | |
| created_at | TIMESTAMPTZ | ✓ | NOW() | |
| updated_at | TIMESTAMPTZ | ✓ | NOW() | |

---

### refund

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| refund_id | UUID | ✓ | gen_random_uuid() | PK |
| payment_id | UUID | ✓ | | FK → payment.payment_id |
| order_product_id | UUID | | NULL | FK → order_product.order_product_id. 전체 환불 시 NULL |
| user_id | UUID | ✓ | | FK → user.user_id |
| refund_amount | INT | ✓ | | 환불 금액. 전체 환불 시 payment.total_amount와 동일 |
| reason | TEXT | | NULL | 환불 사유 |
| status | refund_status_type | ✓ | REQUESTED | REQUESTED / COMPLETED / FAILED |
| requested_at | TIMESTAMPTZ | ✓ | NOW() | |
| completed_at | TIMESTAMPTZ | | NULL | PG사 환불 처리 완료 일시 |
| created_at | TIMESTAMPTZ | ✓ | NOW() | |
| updated_at | TIMESTAMPTZ | ✓ | NOW() | |

---

## Settlement Service

### settlement_batch

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| batch_id | UUID | ✓ | gen_random_uuid() | PK |
| batch_no | VARCHAR(100) | ✓ | | 정산 배치 번호. UNIQUE 권장. 예: SETTLE-202606-001 |
| period_start | DATE | ✓ | | 정산 대상 기간 시작일 |
| period_end | DATE | ✓ | | 정산 대상 기간 종료일 |
| status | settlement_status_type | ✓ | | PROCESSING / COMPLETED / FAILED / CANCELLED |
| trigger_type | trigger_type_enum | ✓ | | SCHEDULED / MANUAL |
| failure_reason | VARCHAR(1000) | | NULL | 실패 시 원인 메시지 |
| executed_at | TIMESTAMPTZ | | NULL | 배치 실행 일시 |
| created_at | TIMESTAMPTZ | ✓ | | |
| updated_at | TIMESTAMPTZ | ✓ | | |

---

### settlement

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| settlement_id | UUID | ✓ | gen_random_uuid() | PK |
| settlement_batch_id | UUID | | NULL | FK → settlement_batch.batch_id |
| seller_id | UUID | ✓ | | FK → seller.seller_id |
| period_start | DATE | ✓ | | 집계 기간 시작일 |
| period_end | DATE | ✓ | | 집계 기간 종료일 |
| product_count | INT | ✓ | 0 | 집계에 포함된 항목 수 |
| total_amount | NUMERIC(12,2) | ✓ | 0 | 수수료 차감 전 정산 기준액 |
| settlement_total_amount | NUMERIC(12,2) | ✓ | 0 | 실제 지급 예정액 |
| fee_total_amount | NUMERIC(12,2) | ✓ | | 플랫폼 총 수수료 |
| refund_amount | NUMERIC(12,2) | | NULL | 환불 금액 |
| settlement_status | settlement_status_type | ✓ | | PROCESSING / COMPLETED / FAILED / CANCELLED |
| payout_status | payout_status_type | ✓ | | PENDING / PAID / FAILED |
| failed_reason | VARCHAR(1000) | | NULL | 정산 실패 원인 |
| calculated_at | TIMESTAMPTZ | ✓ | | 배치 산정 시각 |
| confirmed_at | TIMESTAMPTZ | | NULL | 정산 확정 시각 |
| paid_at | TIMESTAMPTZ | | NULL | 지급 완료 시각 |
| payout_reference | VARCHAR(100) | | NULL | 외부 송금 시스템 거래 참조 |
| created_at | TIMESTAMPTZ | ✓ | | |
| updated_at | TIMESTAMPTZ | ✓ | | |

---

### settlement_detail

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| settlement_detail_id | UUID | ✓ | gen_random_uuid() | PK |
| settlement_id | UUID | ✓ | | FK → settlement.settlement_id |
| order_product_id | UUID | | NULL | FK → order_product.order_product_id |
| line_amount | NUMERIC(12,2) | ✓ | | 거래 금액 |
| fee_rate | NUMERIC(5,4) | ✓ | | 적용된 수수료율. 0.1000 = 10% |
| fee_amount | NUMERIC(12,2) | ✓ | | 플랫폼 수수료 |
| line_settlement_amount | NUMERIC(12,2) | ✓ | | 라인별 정산액 |
| line_type | settlement_line_type | ✓ | | SALE / REFUND / ADJUSTMENT |
| occurred_at | TIMESTAMPTZ | ✓ | | 원천 거래 발생 시각. 기간 귀속 판단 기준 |
| created_at | TIMESTAMPTZ | ✓ | | |
