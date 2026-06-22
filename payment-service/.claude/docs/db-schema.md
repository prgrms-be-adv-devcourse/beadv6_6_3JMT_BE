# DB 테이블 구조

payment-service가 소유하는 테이블 요약. **원본 DDL**: `src/main/resources/sql/ddl.sql`

모노레포 전체 ERD: `../../../docs/erd/schema.md`

---

## ENUM 타입

### payment_status
| 값 | 설명 |
|---|---|
| `READY` | Payment 레코드 생성, PG 요청 전 |
| `REQUESTED` | PG사에 결제 요청 전송 완료 |
| `PAID` | PG사 결제 승인 완료 |
| `FAILED` | PG사 결제 실패 |
| `CANCELING` | 취소 이벤트 수신, PG 취소 진행 중 |
| `CANCELED` | PG 취소 완료 |
| `CANCEL_FAILED` | PG 취소 실패 |
| `REFUNDING` | 환불 요청 접수, PG 환불 진행 중 |
| `REFUNDED` | 환불 완료 |
| `UNKNOWN` | PG 응답 불명확, 수동 확인 필요 |

### refund_status
| 값 | 설명 |
|---|---|
| `REQUESTED` | 환불 요청 접수 |
| `COMPLETED` | PG사 환불 처리 완료 |
| `FAILED` | PG사 환불 실패 |

---

## payment 테이블

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|---|---|---|---|---|
| `id` | UUID | ✅ | — | PK |
| `order_id` | UUID | ✅ | — | 연결된 주문 ID |
| `user_id` | UUID | ✅ | — | 결제 요청 사용자 ID |
| `pg_tx_id` | VARCHAR(100) | ✅ | — | 토스페이먼츠 paymentKey (중복 처리 방지 기준값) |
| `status` | payment_status | ✅ | `READY` | 결제 상태 |
| `payment_method` | VARCHAR(30) | ✅ | — | 결제 수단 (예: CARD) |
| `provider` | VARCHAR(30) | ✅ | — | PG사 구분 (예: TOSS_PAYMENTS) |
| `is_test` | BOOLEAN | ✅ | `FALSE` | 테스트 결제 여부 |
| `total_amount` | INT | ✅ | — | 최종 결제 요청 금액 (product_amount - discount_amount) |
| `product_amount` | INT | ✅ | — | 상품 원금액 |
| `discount_amount` | INT | ✅ | `0` | 할인 금액 (쿠폰·포인트 확장 시 사용) |
| `approved_amount` | INT | — | NULL | PG사 실제 승인 금액 (승인 전 NULL) |
| `canceled_amount` | INT | ✅ | `0` | 취소된 금액 (부분 취소 도입 시 활용) |
| `idempotency_key` | VARCHAR(255) | ✅ | — | 중복 결제 방지 키. 형식: `pay-{order_id}` |
| `failure_code` | VARCHAR(100) | — | NULL | PG사 결제 실패 코드 |
| `failure_reason` | TEXT | — | NULL | PG사 결제 실패 상세 사유 |
| `cancel_reason` | TEXT | — | NULL | 구매자 취소 사유 |
| `request_payload` | JSONB | — | NULL | PG사 결제 요청 원문 JSON (분쟁·디버깅용) |
| `response_payload` | JSONB | — | NULL | PG사 응답 원문 JSON (분쟁·디버깅용) |
| `requested_at` | TIMESTAMPTZ | — | NULL | PG사 결제 요청 전송 일시 |
| `approved_at` | TIMESTAMPTZ | — | NULL | PG사 결제 승인 완료 일시 |
| `failed_at` | TIMESTAMPTZ | — | NULL | PG사 결제 실패 일시 |
| `canceled_at` | TIMESTAMPTZ | — | NULL | PG사 취소 완료 일시 |
| `refunded_at` | TIMESTAMPTZ | — | NULL | PG사 환불 완료 일시 |
| `created_at` | TIMESTAMPTZ | ✅ | `NOW()` | 생성 일시 |
| `updated_at` | TIMESTAMPTZ | ✅ | `NOW()` | 수정 일시 |

---

## refund 테이블

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|---|---|---|---|---|
| `id` | UUID | ✅ | — | PK |
| `payment_id` | UUID | ✅ | — | FK → payment(id) |
| `order_product_id` | UUID | — | NULL | 부분 환불 대상 OrderProduct ID. 전체 환불 시 NULL |
| `user_id` | UUID | ✅ | — | 환불 요청 사용자 ID |
| `refund_amount` | INT | ✅ | — | 환불 금액 (전체 환불 시 payment.total_amount와 동일) |
| `reason` | TEXT | — | NULL | 환불 사유 |
| `status` | refund_status | ✅ | `REQUESTED` | 환불 상태 |
| `requested_at` | TIMESTAMPTZ | ✅ | `NOW()` | 환불 요청 접수 일시 |
| `completed_at` | TIMESTAMPTZ | — | NULL | PG사 환불 처리 완료 일시 |
| `created_at` | TIMESTAMPTZ | ✅ | `NOW()` | 생성 일시 |
| `updated_at` | TIMESTAMPTZ | ✅ | `NOW()` | 수정 일시 |
