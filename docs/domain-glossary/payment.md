# Payment Service 도메인 용어 사전

---

## 결제 (payment)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | payment_id | UUID | ✓ | gen_random_uuid() | PK |
| 주문 ID * | order_id | UUID | ✓ | | FK → order.order_id |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.user_id |
| PG 거래 ID * | pg_tx_id | VARCHAR(100) | ✓ | | 토스페이먼츠 paymentKey. 중복 처리 방지 기준값 |
| 결제 상태 * | status | payment_status_type | ✓ | READY | READY / REQUESTED / PAID / FAILED / CANCELING / CANCELED / CANCEL_FAILED / REFUNDED / UNKNOWN |
| 결제 수단 * | payment_method | VARCHAR(30) | ✓ | | CARD 등 |
| 결제 제공자 * | provider | VARCHAR(30) | ✓ | | TOSS_PAYMENTS 등 |
| 테스트 결제 여부 * | is_test | BOOLEAN | ✓ | FALSE | |
| 총 결제 금액 * | total_amount | INT | ✓ | | 최종 결제 요청 금액 (product_amount - discount_amount) |
| 상품 금액 * | product_amount | INT | ✓ | | 상품 원금액 |
| 할인 금액 * | discount_amount | INT | ✓ | 0 | 쿠폰·포인트 확장 시 사용 |
| 승인 금액 | approved_amount | INT | | NULL | PG사 실제 승인 금액. 승인 전 NULL |
| 취소/환불 금액 * | canceled_amount | INT | ✓ | 0 | 취소된 누적 금액. 부분 취소 도입 시 활용 |
| 멱등키 * | idempotency_key | VARCHAR(255) | ✓ | | 중복 결제 방지 키. 형식: pay-{order_id}. UNIQUE |
| 실패 코드 | failure_code | VARCHAR(100) | | NULL | PG사 결제 실패 코드 |
| 실패 사유 | failure_reason | TEXT | | NULL | PG사 결제 실패 상세 사유 |
| 취소 사유 | cancel_reason | TEXT | | NULL | 구매자 취소 사유 |
| 결제 요청 전문 | request_payload | JSONB | | NULL | PG사 결제 요청 원문. 분쟁·디버깅용 |
| 결제 응답 전문 | response_payload | JSONB | | NULL | PG사 응답 원문. 분쟁·디버깅용 |
| 결제 요청 일시 | requested_at | TIMESTAMPTZ | | NULL | |
| 결제 승인 일시 | approved_at | TIMESTAMPTZ | | NULL | |
| 결제 실패 일시 | failed_at | TIMESTAMPTZ | | NULL | |
| 결제 취소 일시 | canceled_at | TIMESTAMPTZ | | NULL | |
| 환불 완료 일시 | refunded_at | TIMESTAMPTZ | | NULL | |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | NOW() | |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | NOW() | |

---

## 환불 (refund)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | refund_id | UUID | ✓ | gen_random_uuid() | PK |
| 결제 ID * | payment_id | UUID | ✓ | | FK → payment.payment_id |
| 주문 상품 ID | order_product_id | UUID | | NULL | FK → order_product.order_product_id. 전체 환불 시 NULL |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.user_id |
| 환불 금액 * | refund_amount | INT | ✓ | | 전체 환불 시 payment.total_amount와 동일 |
| 환불 사유 | reason | TEXT | | NULL | |
| 환불 상태 * | status | refund_status_type | ✓ | REQUESTED | REQUESTED / COMPLETED / FAILED |
| 환불 요청 일시 * | requested_at | TIMESTAMPTZ | ✓ | NOW() | |
| 환불 완료 일시 | completed_at | TIMESTAMPTZ | | NULL | PG사 환불 처리 완료 일시 |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | NOW() | |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | NOW() | |
