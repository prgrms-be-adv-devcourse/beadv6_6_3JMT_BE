# 스키마 레퍼런스

`init-script/postgres/schema.sql` 기준. 실제 DDL이 기준이며 이 문서는 열람용 미러다.

@docs/api-spec 문서가 완성된 이후에 협의 후 변경! 해당 문서는 변경하면 안됩니다.

---

## ENUM 타입

| 타입명 | 값 |
|-------|-----|
| `user_status_type` | ACTIVE / BLOCKED / WITHDRAWN |
| `user_role_type` | BUYER / SELLER / ADMIN |
| `auth_provider_type` | KAKAO / NAVER / GOOGLE |
| `product_status_type` | DRAFT / PENDING_REVIEW / ON_SALE / REJECTED / STOPPED / SUPERSEDED |
| `amount_type_enum` | FREE / PAID |
| `order_status_type` | CREATED / COMPLETED / FAILED / REFUND_REQUESTED / PARTIAL_REFUNDED / ALL_REFUNDED |
| `order_product_status_type` | PENDING / PAID / FAILED / REFUND_REQUESTED / REFUNDED |
| `payment_status_type` | READY / REQUESTED / PAID / FAILED / UNKNOWN |
| `refund_status_type` | REQUESTED / COMPLETED / FAILED |
| `settlement_batch_status_type` | PROCESSING / COMPLETED / FAILED / RETRY_REQUESTED / CANCELLED |
| `settlement_status_type` | PENDING_APPROVAL / SETTLEMENT_ON_HOLD / APPROVED / CANCELLED |
| `payout_status_type` | NOT_READY / READY / PAYOUT_REQUESTED / PAYOUT_ON_HOLD / PAID |
| `trigger_type_enum` | SCHEDULED / MANUAL |
| `settlement_line_type` | SALE / REFUND / ADJUSTMENT |
| `settlement_source_line_type` | PAID / REFUND |
| `review_status_type` | ACTIVE / HIDDEN |

> `seller_status_type`는 쓰지 않는다 — 별도 `seller` 테이블이 없다(User Service 섹션 참고).
> `settlement_batch_status_type`(`settlement_batch.status`)과 `settlement_status_type`
> (`settlement.settlement_status`)은 값 집합이 서로 다른 별개 타입이다.

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
| created_at        | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |
| updated_at        | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |

> `role`은 `user` 테이블 컬럼이 아니다. 사용자 한 명이 여러 역할을 동시에 가질 수 있어(예: SELLER
> 승인 후에도 BUYER 유지) 별도 `user_role` 조인 테이블로 관리한다(`User.roles`, `@ElementCollection`).
> API가 노출하는 대표 역할은 `User.getPrimaryRole()`이 ADMIN > SELLER > BUYER 우선순위로 계산한다.

---

### user_role

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| user_id | UUID | ✓ | | PK(복합, user_id+role). FK → user.id |
| role | user_role_type | ✓ | | PK(복합). BUYER / SELLER / ADMIN |

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

### 판매자(seller) 식별자

별도 `seller` 테이블을 두지 않는다. 서비스 간 판매자 식별자는 `user.id`로 통일한다 — 판매자도
`user` 테이블의 한 행이며, `user_role`에 `SELLER` 행이 추가된 사용자일 뿐이다. `product.seller_id`,
`order_product.seller_id`, `settlement.seller_id`는 모두 이 `user.id` 값을 그대로 담는다(서비스
경계를 넘는 크로스 스키마 FK 제약은 걸지 않는다 — 각 서비스는 독립 스키마를 쓴다).

판매자 등록 신청·심사 이력은 user-service의 `seller_register` 테이블(이 문서 범위 밖 — 서비스
내부 워크플로 상태이며 다른 서비스가 참조하지 않는다)이 별도로 관리한다.

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

### product

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| id | UUID | ✓ | gen_random_uuid() | PK |
| parent_id | UUID | | NULL | FK → product.id. 최초 등록 시 NULL, 버전업 시 원본 상품 id |
| seller_id | UUID | ✓ | | = user.id (판매자 식별자. 별도 seller 테이블 없음 — User Service 섹션 참고) |
| major_version | SMALLINT | ✓ | 1 | 메이저 버전. MAJOR 선택 시 +1, patch_version 0 리셋 |
| patch_version | SMALLINT | ✓ | 0 | 패치 버전. PATCH 선택 시 +1. 표기: major.patch |
| change_reason | VARCHAR(500) | | NULL | 버전업 변경 사유 |
| name | VARCHAR(200) | ✓ | | 상품명 |
| description | TEXT | ✓ | | 상품 상세 설명 |
| product_type | VARCHAR(50) | ✓ | | 상품 유형. PROMPT / NOTION / PPT / EXCEL |
| model | VARCHAR(100) | | NULL | 판매자가 입력하는 대상 AI 모델명 (예: GPT-4o, Midjourney v6) |
| amount_type | VARCHAR(20) | ✓ | PAID | FREE / PAID (CHECK constraint) |
| amount | INT | ✓ | 0 | 판매 가격 |
| thumbnail_url | TEXT | | NULL | 대표 이미지 URL. NULL이면 기본 이미지 |
| image_urls | TEXT | | NULL | 추가 이미지 URL 목록 (쉼표 구분, 최대 5개) |
| content | TEXT | | NULL | 프롬프트/템플릿 원문. **외부 응답 노출 금지** |
| file_url | TEXT | | NULL | 유형별 산출물 파일 스토리지 키 (PPT/EXCEL). 판매자 상세만 presigned 노출 |
| external_url | TEXT | | NULL | 외부 노션 링크 원문 (NOTION). 공개 응답 노출 금지 |
| badge | VARCHAR(50) | | NULL | 상품 뱃지 (`신규` 등) |
| status | VARCHAR(30) | ✓ | DRAFT | DRAFT / PENDING_REVIEW / ON_SALE / REJECTED / STOPPED / SUPERSEDED (CHECK constraint). SUPERSEDED = 버전업 승인 시 밀려난 이전 ON_SALE row |
| rejection_reason | VARCHAR(1000) | | NULL | 검수 반려 사유. REJECTED 상태에서만 유효 |
| has_context | BOOLEAN | ✓ | false | AI 검수 체크리스트: 맥락 명시 여부 |
| has_objective | BOOLEAN | ✓ | false | AI 검수 체크리스트: 목표 명시 여부 |
| has_nuance | BOOLEAN | ✓ | false | AI 검수 체크리스트: 뉘앙스 명시 여부 |
| has_tone | BOOLEAN | ✓ | false | AI 검수 체크리스트: 톤 명시 여부 |
| has_examples | BOOLEAN | ✓ | false | AI 검수 체크리스트: 예시 포함 여부 |
| has_execution | BOOLEAN | ✓ | false | AI 검수 체크리스트: 실행 지침 포함 여부 |
| has_role_assignment | BOOLEAN | ✓ | false | AI 검수 체크리스트: 역할 부여 포함 여부. 위 7개는 ai-service PRODUCT_INSPECTION_COMPLETED 이벤트 payload 값을 승인/반려 시 그대로 저장한다(#671) |
| sales_count | INT | ✓ | 0 | 누적 판매 수 |
| view_count | INT | ✓ | 0 | 조회 수 |
| wish_count | INT | ✓ | 0 | 찜 수 |
| tags | TEXT | | NULL | 판매자 지정 태그 (쉼표 구분 문자열, TagsConverter 사용) |
| content_hash | VARCHAR(64) | | NULL | 본문 SHA-256(PROMPT 전용). 복제 탐지용 — 검수 주체가 같은 값을 가진 타 판매자 상품을 조회한다. embedding_source_hash와 다른 값 |
| embedding | vector(1536) | | NULL | 상품 임베딩 (text-embedding-3-small). pgvector 타입이라 엔티티 미매핑, 네이티브 쿼리로만 접근. ON_SALE 부분 HNSW 인덱스 |
| embedding_source_hash | VARCHAR(64) | | NULL | 임베딩 원문의 SHA-256. 값이 같으면 재생성을 건너뛴다 |
| created_at | TIMESTAMPTZ | ✓ | | |
| updated_at | TIMESTAMPTZ | ✓ | | |
| deleted_at | TIMESTAMPTZ | | NULL | 소프트 삭제 일시 |

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
| order_status | order_status_type | ✓ | | CREATED / COMPLETED / FAILED / REFUND_REQUESTED / PARTIAL_REFUNDED / ALL_REFUNDED. 생성 시 CREATED로 고정 |
| completed_at | TIMESTAMPTZ | | NULL | 결제 완료 시각(V2에서 paid_at 대체) |
| refunded_at | TIMESTAMPTZ | | NULL | 전체 환불(ALL_REFUNDED) 확정 시각 |
| created_at | TIMESTAMPTZ | ✓ | | 불변 |
| updated_at | TIMESTAMPTZ | ✓ | | |

> `total_product_count`(V2에서 제거), `paid_at`(V2에서 completed_at으로 대체), `canceled_at`(V2에서
> 제거)은 더 이상 컬럼이 아니다. 상품 수는 `order_product` 건수로 계산한다(`Order.getTotalProductCount()`).

---

### order_product

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| order_product_id | UUID | ✓ | gen_random_uuid() | PK |
| order_id | UUID | ✓ | | FK → order.order_id |
| buyer_id | UUID | | NULL | = order.buyer_id 복제(V7). 상품별 대기중(PENDING) 중복 주문 방지 UNIQUE(buyer_id, product_id) 부분 인덱스 기준 |
| product_id | UUID | ✓ | | FK → product.product_id |
| seller_id | UUID | ✓ | | = user.id (판매자 식별자. 별도 seller 테이블 없음). 정산 기준 |
| product_title_snapshot | VARCHAR(200) | ✓ | | 구매 당시 상품명 스냅샷 |
| product_amount_snapshot | INT | ✓ | | 구매 당시 가격 스냅샷 |
| order_product_status | order_product_status_type | ✓ | | PENDING / PAID / FAILED / REFUND_REQUESTED / REFUNDED |
| downloaded | BOOLEAN | ✓ | | 다운로드 여부 |
| created_at | TIMESTAMPTZ | ✓ | | 불변 |
| refunded_at | TIMESTAMPTZ | | NULL | 환불 완료 시각 |
| updated_at | TIMESTAMPTZ | ✓ | | |

> `product_type_snapshot`·`product_model_snapshot`·`canceled_at`(모두 V2에서 제거)은 더 이상 컬럼이
> 아니다. 취소는 별도 상태 없이 FAILED로 흡수한다(`OrderProductStatus`).

---

## Payment Service

### payment

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| payment_id | UUID | ✓ | gen_random_uuid() | PK |
| order_id | UUID | ✓ | | FK → order.order_id |
| user_id | UUID | ✓ | | FK → user.user_id |
| payment_key | VARCHAR(255) | ✓ | | 토스페이먼츠 paymentKey(V7에서 pg_tx_id → payment_key로 rename). UNIQUE. 별도 idempotency_key 컬럼 없이 이 값이 멱등키를 겸한다 |
| status | payment_status_type | ✓ | | READY / REQUESTED / PAID / FAILED / UNKNOWN. 생성 시 READY로 고정 |
| payment_method | VARCHAR(30) | ✓ | | CARD 등 |
| provider | VARCHAR(30) | ✓ | | TOSS_PAYMENTS 등 |
| total_amount | INT | ✓ | | 결제 요청 금액 |
| approved_amount | INT | | NULL | PG사 실제 승인 금액. 승인 전 NULL |
| failure_code | VARCHAR(100) | | NULL | PG사 결제 실패 코드 |
| failure_reason | TEXT | | NULL | PG사 결제 실패 상세 사유 |
| request_payload | JSONB | | NULL | PG사 결제 요청 원문. 분쟁·디버깅용 |
| response_payload | JSONB | | NULL | PG사 응답 원문. 분쟁·디버깅용 |
| requested_at | TIMESTAMPTZ | | NULL | |
| approved_at | TIMESTAMPTZ | | NULL | |
| failed_at | TIMESTAMPTZ | | NULL | |
| created_at | TIMESTAMPTZ | ✓ | | |
| updated_at | TIMESTAMPTZ | ✓ | | |

> `is_test`(V6에서 제거), `product_amount`/`discount_amount`/`idempotency_key`/`refunded_at`(실제로
> 존재한 적 없거나 V5에서 제거)은 더 이상 컬럼이 아니다. 환불 발생 여부는 payment.status가 아니라
> `refund` 테이블로만 판단한다(PARTIAL_REFUNDED/ALL_REFUNDED 상태는 V5에서 제거됐다).

---

### refund

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| refund_id | UUID | ✓ | gen_random_uuid() | PK |
| payment_id | UUID | ✓ | | FK → payment.payment_id |
| refund_request_id | UUID | ✓ | | 환불 요청 dedup 키(V4). 같은 order_product에 대한 재환불(복수 부분환불)을 허용하기 위해 (payment_id, order_product_id) 대신 이 값을 UNIQUE로 쓴다 |
| refund_amount | INT | ✓ | | 환불 금액 |
| reason | TEXT | | NULL | 환불 사유 |
| failure_code | VARCHAR(50) | | NULL | PG사 환불 실패 코드(V10) |
| failure_reason | TEXT | | NULL | 환불 실패 상세 사유(V9) |
| status | refund_status_type | ✓ | | REQUESTED / COMPLETED / FAILED. 생성 시 REQUESTED로 고정 |
| requested_at | TIMESTAMPTZ | ✓ | | |
| completed_at | TIMESTAMPTZ | | NULL | PG사 환불 처리 완료 일시 |
| failed_at | TIMESTAMPTZ | | NULL | 환불 실패 시각(V9) |
| created_at | TIMESTAMPTZ | ✓ | | |
| updated_at | TIMESTAMPTZ | ✓ | | |

> `order_product_id`·`user_id`(둘 다 V4에서 제거)는 더 이상 컬럼이 아니다.

---

## Settlement Service

### settlement_batch

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| batch_id | UUID | ✓ | gen_random_uuid() | PK |
| batch_no | VARCHAR(100) | ✓ | | 정산 배치 번호. UNIQUE. 예: SETTLE-202606-001 |
| job_instance_id | BIGINT | | NULL | Spring Batch `batch_job_instance.job_instance_id`. UNIQUE(NULL 제외 부분 인덱스, V2) |
| version | BIGINT | ✓ | 0 | 낙관적 락(`@Version`, V2) |
| period_start | DATE | ✓ | | 정산 대상 기간 시작일 |
| period_end | DATE | ✓ | | 정산 대상 기간 종료일 |
| status | settlement_batch_status_type | ✓ | | PROCESSING / COMPLETED / FAILED / RETRY_REQUESTED / CANCELLED. RETRY_REQUESTED는 V2에서 추가 |
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
| seller_id | UUID | ✓ | | = user.id (판매자 식별자. 별도 seller 테이블 없음) |
| period_start | DATE | ✓ | | 집계 기간 시작일 |
| period_end | DATE | ✓ | | 집계 기간 종료일 |
| product_count | INT | ✓ | 0 | 집계에 포함된 항목 수 |
| total_amount | NUMERIC(12,2) | ✓ | 0 | 수수료 차감 전 정산 기준액 |
| settlement_total_amount | NUMERIC(12,2) | ✓ | 0 | 실제 지급 예정액 |
| fee_total_amount | NUMERIC(12,2) | ✓ | | 플랫폼 총 수수료 |
| refund_amount | NUMERIC(12,2) | | NULL | 환불 금액 |
| settlement_status | settlement_status_type | ✓ | PENDING_APPROVAL | PENDING_APPROVAL / SETTLEMENT_ON_HOLD / APPROVED / CANCELLED. DB DEFAULT는 V3에서 추가, 생성 시 엔티티도 PENDING_APPROVAL로 고정 |
| payout_status | payout_status_type | ✓ | NOT_READY | NOT_READY / READY / PAYOUT_REQUESTED / PAYOUT_ON_HOLD / PAID. DB DEFAULT는 V3에서 추가, 생성 시 엔티티도 NOT_READY로 고정 |
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

---

### settlement_source_line

정산 원장(source ledger) 테이블. order-service가 발행하는 결제·환불 Kafka 이벤트를 원시 라인
그대로 적재하고, 정산 배치가 이를 판매자·기간별로 집계해 `settlement`/`settlement_detail`을
만든다. V1부터 존재하며 `settlement`과 달리 배치가 소비하기 전(= 아직 어느 정산 건에도 묶이지
않은 상태)에도 먼저 저장된다.

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|------|------|:--------:|--------|------|
| settlement_source_line_id | UUID | ✓ | gen_random_uuid() | PK |
| event_id | UUID | ✓ | | 원천 Kafka 이벤트 ID. UNIQUE — Consumer 멱등성 처리 기준 |
| line_type | settlement_source_line_type | ✓ | | PAID / REFUND |
| order_id | UUID | | NULL | 원천 주문 ID |
| order_product_id | UUID | ✓ | | FK → order_product.order_product_id |
| seller_id | UUID | ✓ | | = user.id (판매자 식별자. 별도 seller 테이블 없음) |
| line_amount | NUMERIC(12,2) | ✓ | | 라인 금액. 항상 양수(부호는 line_type으로 구분) |
| occurred_at | TIMESTAMPTZ | ✓ | | 원천 거래 발생 시각 |
| settlement_id | UUID | | NULL | FK → settlement.settlement_id. 정산 배치에 편입되기 전까지 NULL(`isSettled()`) |
| created_at | TIMESTAMPTZ | ✓ | | |
| updated_at | TIMESTAMPTZ | ✓ | | |
