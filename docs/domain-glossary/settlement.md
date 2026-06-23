# Settlement Service 도메인 용어 사전

---

## 정산 배치 (settlement_batch)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | batch_id | UUID | ✓ | gen_random_uuid() | PK |
| 배치 번호 * | batch_no | VARCHAR(100) | ✓ | | 사람이 식별하기 위한 번호. 예: SETTLE-202606-001. UNIQUE |
| 정산 기간 시작 * | period_start | DATE | ✓ | | 정산 대상 기간 시작일 |
| 정산 기간 종료 * | period_end | DATE | ✓ | | 정산 대상 기간 종료일 |
| 배치 상태 * | status | settlement_status_type | ✓ | | PROCESSING / COMPLETED / FAILED / CANCELLED |
| 실행 방식 * | trigger_type | trigger_type_enum | ✓ | | SCHEDULED / MANUAL |
| 실패 사유 | failure_reason | VARCHAR(1000) | | NULL | status가 FAILED일 때 사용 |
| 실행 일시 | executed_at | TIMESTAMPTZ | | NULL | |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | | |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | | |

---

## 정산 (settlement)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | settlement_id | UUID | ✓ | gen_random_uuid() | PK |
| 정산 배치 ID | settlement_batch_id | UUID | | NULL | FK → settlement_batch.batch_id |
| 판매자 ID * | seller_id | UUID | ✓ | | FK → seller.seller_id |
| 정산 기간 시작 * | period_start | DATE | ✓ | | |
| 정산 기간 종료 * | period_end | DATE | ✓ | | |
| 정산 대상 건수 * | product_count | INT | ✓ | 0 | |
| 전체 매출액 * | total_amount | NUMERIC(12,2) | ✓ | 0 | 수수료 차감 전 정산 기준액 |
| 지급 순액 * | settlement_total_amount | NUMERIC(12,2) | ✓ | 0 | 실제 지급 예정액 |
| 총 수수료액 * | fee_total_amount | NUMERIC(12,2) | ✓ | | 플랫폼 총 수수료 |
| 환불 금액 | refund_amount | NUMERIC(12,2) | | NULL | |
| 정산 상태 * | settlement_status | settlement_status_type | ✓ | | PROCESSING / COMPLETED / FAILED / CANCELLED |
| 지급 상태 * | payout_status | payout_status_type | ✓ | | PENDING / PAID / FAILED |
| 실패 사유 | failed_reason | VARCHAR(1000) | | NULL | |
| 산정 일시 * | calculated_at | TIMESTAMPTZ | ✓ | | 배치 실행 시각 |
| 확정 일시 | confirmed_at | TIMESTAMPTZ | | NULL | |
| 지급 일시 | paid_at | TIMESTAMPTZ | | NULL | |
| 지급 참조 ID | payout_reference | VARCHAR(100) | | NULL | 외부 송금 시스템 거래 참조 |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | | |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | | |

---

## 정산 상세 (settlement_detail)

settlement의 하위 엔티티.

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | settlement_detail_id | UUID | ✓ | gen_random_uuid() | PK |
| 정산 ID * | settlement_id | UUID | ✓ | | FK → settlement.settlement_id |
| 주문 상품 ID | order_product_id | UUID | | NULL | FK → order_product.order_product_id |
| 기여 금액 * | line_amount | NUMERIC(12,2) | ✓ | | 거래 금액 |
| 수수료율 * | fee_rate | NUMERIC(5,4) | ✓ | | 적용된 수수료율. 0.1000 = 10%. 시점 스냅샷 |
| 수수료액 * | fee_amount | NUMERIC(12,2) | ✓ | | 플랫폼 수수료 |
| 지급액 * | line_settlement_amount | NUMERIC(12,2) | ✓ | | 라인별 정산액 |
| 정산 항목 유형 * | line_type | settlement_line_type | ✓ | | SALE / REFUND / ADJUSTMENT |
| 발생 일시 * | occurred_at | TIMESTAMPTZ | ✓ | | 원천 거래 발생 시각. 기간 귀속 판단 기준 |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | | |

---

## 정산 소스 라인 (settlement_source_line)

orderProduct 결제·환불 이벤트를 실시간 수신해 적재하는 정산 원장.
정산 배치가 미정산 라인(`settlement_id IS NULL`)을 판매자·기간으로 모아 `settlement_detail`로 산정한다.

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | settlement_source_line_id | UUID | ✓ | gen_random_uuid() | PK |
| 멱등키 * | event_id | UUID | ✓ | | 이벤트 멱등키. 같은 이벤트 재수신 차단. UNIQUE |
| 이벤트 유형 * | event_type | VARCHAR(30) | ✓ | | 수신한 원본 이벤트 종류. PAID / REFUND |
| 주문 ID | order_id | UUID | | NULL | FK → order.order_id. 참조·추적용 |
| 주문 상품 ID * | order_product_id | UUID | ✓ | | FK → order_product.order_product_id |
| 판매자 ID * | seller_id | UUID | ✓ | | FK → seller.seller_id. 정산 기준 |
| 거래 금액 * | line_amount | NUMERIC(12,2) | ✓ | | 거래 금액(항상 양수). 가산/차감은 event_type으로 구분 |
| 발생 일시 * | occurred_at | TIMESTAMPTZ | ✓ | | 원천 이벤트 발생 시각. 기간 귀속 판단 기준 |
| 정산 ID | settlement_id | UUID | | NULL | FK → settlement.settlement_id. NULL이면 미정산, 정산 반영 시 연결 |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | | 수신 적재 시각 |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | | 정산 연결 시 갱신 |
