# DB 테이블 구조

payment-service가 소유하는 테이블 요약. 스키마는 **Flyway 마이그레이션(`src/main/resources/db/migration/V{n}__*.sql`)** 으로 관리되고, Hibernate는 `ddl-auto: validate`로 엔티티-스키마 일치만 검증한다. 상세 규칙은 [`flyway-migration.md`](../rules/flyway-migration.md) 참조.

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
| `UNKNOWN` | PG 응답 불명확, 수동 확인 필요 |

`PAID`는 환불이 몇 번 발생하든 계속 유지된다 — 환불 발생 여부·누적액은 `refund` 테이블에서만 판단하고 `payment.status`로는 표현하지 않는다(V5 마이그레이션).

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
| `payment_key` | VARCHAR(255) | ✅ | — | 토스페이먼츠 paymentKey. **UNIQUE**(`uk_payment_payment_key`) — 멱등키 겸용(동일 paymentKey 이중 confirm 차단) |
| `status` | payment_status | ✅ | `READY` | 결제 상태 |
| `payment_method` | VARCHAR(30) | ✅ | — | 결제 수단 (예: CARD) |
| `provider` | VARCHAR(30) | ✅ | — | PG사 구분 (예: TOSS_PAYMENTS) |
| `total_amount` | INT | ✅ | — | 결제 요청 금액 (주문 스냅샷 `total_amount` 기준) |
| `approved_amount` | INT | — | NULL | PG사 실제 승인 금액 (승인 전 NULL) |
| `failure_code` | VARCHAR(100) | — | NULL | PG사 결제 실패 코드 |
| `failure_reason` | TEXT | — | NULL | PG사 결제 실패 상세 사유 |
| `request_payload` | JSONB | — | NULL | PG사 결제 요청 원문 JSON (분쟁·디버깅용) |
| `response_payload` | JSONB | — | NULL | PG사 응답 원문 JSON (분쟁·디버깅용) |
| `requested_at` | TIMESTAMPTZ | — | NULL | PG사 결제 요청 전송 일시 |
| `approved_at` | TIMESTAMPTZ | — | NULL | PG사 결제 승인 완료 일시 |
| `failed_at` | TIMESTAMPTZ | — | NULL | PG사 결제 실패 일시 |
| `created_at` | TIMESTAMPTZ | ✅ | `NOW()` | 생성 일시 |
| `updated_at` | TIMESTAMPTZ | ✅ | `NOW()` | 수정 일시 |

**인덱스** (Flyway):

| 인덱스 | 대상 | 목적 |
|---|---|---|
| `uk_payment_payment_key` | UNIQUE (`payment_key`) | 동일 paymentKey 이중 confirm 차단(멱등) |
| `uk_payment_order_paid` | UNIQUE (`order_id`) WHERE `status='PAID'` | 같은 주문 동시 결제 시 두 번째 PAID 전이 차단(동시성 방어). 부분 유니크라 재결제(FAILED 다건)는 허용 |

> 중복 판정은 `existsByOrderIdAndStatusIn(order_id, {PAID, FAILED, UNKNOWN})` 로 수행한다. REQUESTED·READY(진행 중인 시도)만 비차단이다. `FAILED`도 차단 대상이라 한 번 실패한 주문은 같은 orderId로 재결제할 수 없다(새 주문으로만 재시도).

---

## refund 테이블

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|---|---|---|---|---|
| `id` | UUID | ✅ | — | PK |
| `payment_id` | UUID | ✅ | — | FK → payment(id) |
| `refund_request_id` | UUID | ✅ | — | order-service가 발급하는 환불 요청 식별자. **UNIQUE**(`uk_refund_request_id`) — dedup 키(#398) |
| `refund_amount` | INT | ✅ | — | 이번 환불 시도의 금액 |
| `reason` | TEXT | — | NULL | 환불 요청 시 사용자가 입력한 사유(현재 `RefundCommand`에 입력 경로가 없어 항상 NULL) |
| `failure_reason` | TEXT | — | NULL | 환불 실패 사유. `fail()` 호출 시에만 값 설정, `reason`과 독립적으로 보존 |
| `status` | refund_status | ✅ | `REQUESTED` | 환불 상태 |
| `requested_at` | TIMESTAMPTZ | ✅ | `NOW()` | 환불 요청 접수 일시 |
| `completed_at` | TIMESTAMPTZ | — | NULL | PG사 환불 처리 완료 일시 |
| `failed_at` | TIMESTAMPTZ | — | NULL | 환불 실패 처리 일시 |
| `created_at` | TIMESTAMPTZ | ✅ | `NOW()` | 생성 일시 |
| `updated_at` | TIMESTAMPTZ | ✅ | `NOW()` | 수정 일시 |

**인덱스** (Flyway):

| 인덱스 | 대상 | 목적 |
|---|---|---|
| `uk_refund_request_id` | UNIQUE (`refund_request_id`) | 동일 환불 요청 재전송 dedup. 동일 상품에 대한 재환불(복수 부분환불)은 허용된다 |

`order_product_id`/`user_id` 컬럼은 제거되었다(#398) — 둘 다 payment-service 내부에서 읽히지 않는 죽은 컬럼이었고, 상품 단위 추적 책임은 `refund_request_id`를 발급하는 order-service 쪽에 있다.

---

## audit_log 테이블

결제·환불 종결 상태 전이(승인/실패/환불완료/환불실패) 4종에 한해 행위자·시각·사유를 append-only로 기록하는 이력 테이블(#484). 조회 API는 아직 없다 — 저장까지만 구현됨.

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|---|---|---|---|---|
| `id` | UUID | ✅ | — | PK |
| `entity_type` | VARCHAR(20) | ✅ | — | `PAYMENT` / `REFUND` |
| `entity_id` | UUID | ✅ | — | `payment.id` 또는 `refund.id`. FK 없음 — entity_type에 따라 대상 테이블이 갈리는 폴리모픽 참조 |
| `event_type` | VARCHAR(30) | ✅ | — | `PAYMENT_APPROVED` / `PAYMENT_FAILED` / `PAYMENT_REFUNDED` / `PAYMENT_REFUND_FAILED` |
| `actor_id` | UUID | ✅ | — | 행위자 user_id. `Payment.userId`에서 획득(Refund엔 user_id 컬럼 없음 — #398로 제거됨) |
| `new_status` | VARCHAR(20) | ✅ | — | 전이 후 상태 스냅샷 |
| `detail` | TEXT | — | NULL | 실패 사유. `PAYMENT_FAILED`/`PAYMENT_REFUND_FAILED`만 값 있음 |
| `occurred_at` | TIMESTAMPTZ | ✅ | — | 엔티티 기준 실제 발생 시각 |
| `created_at` | TIMESTAMPTZ | ✅ | — | 감사로그 레코드 삽입 시각 |

`updated_at` 없음 — append-only, 절대 수정하지 않는다.

**인덱스** (Flyway):

| 인덱스 | 대상 | 목적 |
|---|---|---|
| `idx_audit_log_entity` | (`entity_type`, `entity_id`) | 엔티티별 이력 조회 |
