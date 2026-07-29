# 정산 원천 집계 대사 설계

## 배경

Settlement Service는 정산 기간에 해당하는 Order 원천을 gRPC로 조회해
`settlement_source_line`에 적재한다. 현재 적재 로직은
`orderProductId + lineType`으로 만든 `eventId`를 멱등키로 사용하지만, 적재 후
Order 원천과 저장 결과가 같은지 확인하지 않는다.

이 설계는 정산 계산 전에 Order 원천과 저장된 source line의 유형별 건수와
금액 합계를 비교한다. 불일치하면 계산과 Outbox 생성을 시작하지 않는다.

## 범위

대사 항목은 다음 네 가지로 제한한다.

- Order PAID 건수와 source line PAID 건수
- Order PAID 금액 합계와 source line PAID 금액 합계
- Order REFUND 건수와 source line REFUND 건수
- Order REFUND 금액 합계와 source line REFUND 금액 합계

다음 항목은 이번 구현에서 비교하지 않는다.

- `orderProductId + lineType`별 누락과 초과
- 개별 라인의 금액 변경
- `sellerId`, `occurredAt`, `orderId` 변경
- Order 응답의 동일 키 중복 여부

개별 차이가 서로 상쇄되어 유형별 건수와 합계가 같으면 대사에 성공한다. 이는
경량 집계 대사를 선택한 결과이며, 계산 결과 대사와 전달 대사는 각각
#642 (이슈), #637 (이슈)의 범위로 유지한다.

## 배치 흐름

기존 정산 Job에 원천 대사 Step을 추가한다.

```text
createSettlementBatchStep
→ retryPendingOutboxStep
→ loadSettlementSourceStep
→ reconcileSettlementSourceStep
→ settlementStep
→ completeSettlementBatchStep
→ flushCurrentBatchOutboxStep
```

`reconcileSettlementSourceStep`은 기존 `OrderSettlementQuery` 포트를 사용해
Order Service의 `GetSettleableLines`를 다시 호출한다. 요청에는 기존과 동일하게
월요일 `periodStart`와 일요일 `periodEnd`를 전달한다.

Order 응답은 애플리케이션 계층에서 `lineType`별 건수와 금액으로 집계한다.
저장 원천은 같은 기간의 `settlement_source_line` 전체를 정산 여부와 관계없이
집계한다. 계산 대상 조회는 기존처럼 `settlementId IS NULL` 조건을 유지한다.

양쪽이 모두 0건이고 합계가 0이면 정상 일치로 처리한다. 금액은
`BigDecimal.compareTo()` 기준으로 비교해 소수점 자릿수 차이는 무시한다.

## 컴포넌트

### 원천 대사 유스케이스

원천 대사 유스케이스는 다음 작업만 조율한다.

1. `OrderSettlementQuery`로 현재 Order 원천을 조회한다.
2. Order 응답을 PAID·REFUND별 건수와 금액 합계로 집계한다.
3. `SettlementSourceAggregateRepository`에서 같은 기간의 저장 원천 집계를 조회한다.
4. 네 가지 집계값을 비교해 대사 결과를 반환한다.

대사 결과에는 Order 집계, source line 집계, 일치 여부와 불일치 항목이
포함된다. 유스케이스는 Spring Batch 타입에 의존하지 않는다.

### 저장 원천 집계 조회

`SettlementSourceAggregateRepository`에 기간별 유형 집계 조회 계약을 추가한다.
영속성 어댑터는 DB에서 `line_type`별 `count`와 `sum`을 계산해 반환한다.
정산 여부와 무관한 기간 전체 라인이 대상이며 기간은
`[periodStart 00:00, periodEnd + 1일 00:00)`으로 적용한다.

### 대사 Tasklet

대사 Tasklet은 유스케이스의 결과를 `StepExecutionContext`에 기록한다.

```text
reconciliationStatus
orderPaidCount
orderPaidAmount
sourcePaidCount
sourcePaidAmount
orderRefundCount
orderRefundAmount
sourceRefundCount
sourceRefundAmount
```

금액은 직렬화 호환성을 위해 문자열로 기록한다. 이 Context는
`batch_step_execution_context`에 저장되며 Spring Batch 메타데이터를
정리하기 전까지 성공·실패 여부와 관계없이 남는다.

## 성공과 실패 처리

집계가 모두 일치하면 대사 Step은 `COMPLETED`로 끝나고 정산 계산 Step으로
진행한다.

집계가 하나라도 다르면 다음 순서로 처리한다.

1. 대사 집계와 `MISMATCHED` 결과를 StepExecutionContext에 기록한다.
2. `SettlementBatch`를 `RECONCILIATION_FAILED`로 전이한다.
3. 기존 `failureReason`에 불일치 집계 항목을 요약한다.
4. Step ExitCode를 `RECONCILIATION_FAILED`로 설정한다.
5. Tasklet 트랜잭션을 정상 커밋한다.
6. Job Flow는 해당 ExitCode를 보고 Job을 실패 종료한다.

예외를 던져 Tasklet 트랜잭션을 롤백하지 않는 이유는 대사 Context와 업무 실패
상태를 함께 보존하기 위해서다. Job 종료 리스너는 이미
`RECONCILIATION_FAILED`인 배치를 일반 `FAILED`로 덮어쓰지 않는다.

Order gRPC 호출 실패나 DB 조회 실패 같은 기술 장애는 기존 예외 흐름을
사용하고 배치를 `FAILED`로 처리한다. 이때도 계산 Step으로 진행하지 않도록
대사 Step의 `FAILED` ExitCode를 명시적으로 실패 종료한다.

## 데이터베이스 변경

별도의 대사 테이블이나 집계 컬럼을 만들지 않는다. V6 마이그레이션에서는
`settlement_batch.status`의 CHECK 제약에 `RECONCILIATION_FAILED`만 추가한다.

기존 컬럼은 다음처럼 사용한다.

- `status`: `RECONCILIATION_FAILED`
- `failure_reason`: 불일치한 집계 항목 요약
- `executed_at`: 대사 실패 확정 시각

## 재실행과 Outbox

기존 Spring Batch 재시작은 소스 조회나 정산 계산 같은 기술 실패를 실패
지점부터 복구하는 용도로 유지한다. #639 (이슈)는 기존 JobInstance 재시작
범위나 성공한 Step 재실행 설정을 바꾸지 않는다.

대사 실패 후 동일 기간을 다시 처리할 때는 원천 적재부터 시작하는 새 배치가
필요하다. 다만 #639 (이슈)는 기존 Job 파라미터와 실행 API를 바꾸지 않으므로,
새 JobInstance를 만드는 식별 파라미터와 운영 Admin API·일회성 Kubernetes
Job 생성은 별도 이슈로 다룬다. 해당 실행 경로가 추가되면 기존 `eventId`
멱등키가 source line 중복 저장을 막는다.

Outbox 발행 실패는 기존 정책을 유지한다.

- `PENDING`: 다음 정산 Job의 `retryPendingOutboxStep`에서 재시도
- `FAILED`: 별도 `outboxRedriveJob` 대상

원천 대사 실패는 정산 계산 전에 발생하므로 해당 배치의 Settlement와 Outbox는
생성되지 않는다.

## 테스트 전략

도메인, 애플리케이션, 배치, 마이그레이션 순서로 검증한다.

- `SettlementBatch`가 `PROCESSING`에서 `RECONCILIATION_FAILED`로 전이한다.
- 이미 대사 실패한 배치를 일반 실패 리스너가 `FAILED`로 덮어쓰지 않는다.
- PAID·REFUND 건수와 금액이 모두 일치하면 성공한다.
- PAID 건수 또는 금액이 다르면 실패한다.
- REFUND 건수 또는 금액이 다르면 실패한다.
- 양쪽 모두 0건이면 성공한다.
- 대사 Tasklet이 모든 집계값을 StepExecutionContext에 기록한다.
- 불일치 시 `settlementStep`과 Outbox 생성이 실행되지 않는다.
- 동일 기간을 반복 대사해도 같은 입력에는 같은 집계 결과를 반환한다.
- V6 적용 후 `RECONCILIATION_FAILED` 상태를 저장할 수 있다.

## 제외 사항

- Order Service gRPC 계약 변경
- Order DB 직접 조회
- 개별 source line 대사
- 계산 결과 대사
- Seller Settlement 전달 대사
- Admin 재실행 API와 Kubernetes Job 생성
- Outbox 재시도·redrive 정책 변경
