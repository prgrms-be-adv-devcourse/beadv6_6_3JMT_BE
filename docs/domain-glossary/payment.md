# Payment Service 도메인 용어 사전

---

## 결제 (payment)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | payment_id | UUID | ✓ | gen_random_uuid() | PK |
| 주문 ID * | order_id | UUID | ✓ | | FK → order.order_id. `status = 'PAID'`인 결제는 주문당 최대 1건(partial unique index: uk_payment_order_paid) |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.user_id |
| 결제 키 * | payment_key | VARCHAR(255) | ✓ | | 토스페이먼츠 paymentKey. UNIQUE(uk_payment_payment_key). 멱등키 역할을 겸함 — 별도 idempotency_key 컬럼 없음 |
| 결제 상태 * | status | VARCHAR(20) | ✓ | READY | READY / REQUESTED / PAID / FAILED / UNKNOWN |
| 결제 수단 * | payment_method | VARCHAR(30) | ✓ | | CARD 등 |
| 결제 제공자 * | provider | VARCHAR(30) | ✓ | | TOSS_PAYMENTS 등 |
| 총 결제 금액 * | total_amount | INT | ✓ | | 결제 요청 금액 |
| 승인 금액 | approved_amount | INT | | NULL | PG사 실제 승인 금액. 승인 전 NULL |
| 실패 코드 | failure_code | VARCHAR(100) | | NULL | PG사 결제 실패 코드 |
| 실패 사유 | failure_reason | TEXT | | NULL | PG사 결제 실패 상세 사유 |
| 결제 요청 전문 | request_payload | JSONB | | NULL | PG사 결제 요청 원문. 분쟁·디버깅용 |
| 결제 응답 전문 | response_payload | JSONB | | NULL | PG사 응답 원문. 분쟁·디버깅용 |
| 결제 요청 일시 | requested_at | TIMESTAMPTZ | | NULL | |
| 결제 승인 일시 | approved_at | TIMESTAMPTZ | | NULL | |
| 결제 실패 일시 | failed_at | TIMESTAMPTZ | | NULL | |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | NOW() | |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | NOW() | |

> `order_snapshot` 테이블(주문 정보 로컬 캐시)은 order-service gRPC 직접 조회 구조로 전환되며 제거됐다(V3).

---

## 환불 (refund)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | refund_id | UUID | ✓ | gen_random_uuid() | PK |
| 결제 ID * | payment_id | UUID | ✓ | | FK → payment.payment_id |
| 환불 요청 ID * | refund_request_id | UUID | ✓ | | 환불 요청 dedup 키. UNIQUE(uk_refund_request_id). 동일 payment에 대한 재환불(복수 부분환불)을 허용 |
| 환불 금액 * | refund_amount | INT | ✓ | | 부분/전체 환불 모두 가능 |
| 환불 사유 | reason | TEXT | | NULL | |
| 실패 사유 | failure_reason | TEXT | | NULL | PG사 환불 실패 상세 사유 |
| 실패 코드 | failure_code | VARCHAR(50) | | NULL | PG사 환불 실패 코드(또는 내부 사유 코드) |
| 환불 상태 * | status | VARCHAR(20) | ✓ | REQUESTED | REQUESTED / COMPLETED / FAILED |
| 환불 요청 일시 * | requested_at | TIMESTAMPTZ | ✓ | NOW() | |
| 환불 완료 일시 | completed_at | TIMESTAMPTZ | | NULL | PG사 환불 처리 완료 일시 |
| 환불 실패 일시 | failed_at | TIMESTAMPTZ | | NULL | |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | NOW() | |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | NOW() | |

> `order_product_id`·`user_id` 컬럼은 제거됐다. dedup 기준이 (payment_id, order_product_id)에서 `refund_request_id`로 바뀌며(V4), 상품 단위 환불 이력은 이 서비스 로컬에서 더 이상 추적하지 않는다.

---

## 감사 로그 (audit_log)

결제/환불의 종결 상태 전이(요청/승인/실패/환불완료/환불실패) 이력을 append-only로 보존하는 테이블이다(V8, V11).

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | audit_log_id | UUID | ✓ | gen_random_uuid() | PK |
| 주문 ID * | order_id | UUID | ✓ | | FK → order.order_id |
| 대상 엔티티 타입 * | entity_type | VARCHAR(20) | ✓ | | PAYMENT / REFUND |
| 대상 엔티티 ID * | entity_id | UUID | ✓ | | entity_type=PAYMENT면 payment.payment_id, REFUND면 refund.refund_id |
| 이벤트 타입 * | event_type | VARCHAR(30) | ✓ | | PAYMENT_REQUESTED / PAYMENT_APPROVED / PAYMENT_FAILED / REFUND_REQUESTED / REFUND_COMPLETED / REFUND_FAILED |
| 수행자 ID * | actor_id | UUID | ✓ | | payment.user_id (결제·환불 공통) |
| 변경 후 상태 * | new_status | VARCHAR(20) | ✓ | | 전이 결과 상태값(PaymentStatus 또는 RefundStatus 문자열) |
| 실패 코드 | failure_code | VARCHAR(50) | | NULL | PAYMENT_FAILED / REFUND_FAILED일 때의 실패 코드 |
| 상세 | detail | TEXT | | NULL | |
| 발생 일시 * | occurred_at | TIMESTAMPTZ | ✓ | | 실제 상태 전이가 발생한 시각 |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | NOW() | append-only — updated_at 없음 |
