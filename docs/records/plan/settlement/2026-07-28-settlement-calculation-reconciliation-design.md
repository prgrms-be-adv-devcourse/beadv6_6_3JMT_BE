# 정산 계산 결과 대사 설계

## 목적

GitHub #642 (이슈)는 정산 계산이 끝난 뒤 정산 본체, 상세, 원천 라인의 관계를 다시 검증하고 그 결과를 이력으로 남긴다. 대사에 성공한 배치만 완료하고 Outbox 발행 대상으로 전환한다.

이 설계는 계산 결과 대사만 다룬다. #639 (이슈)의 Order 원천 대사와 공통 대사 실패 상태, #637 (이슈)의 User Service 수신·전달 대사는 포함하지 않는다.

## 배치 흐름

기존 계산 Step과 배치 완료 Step 사이에 계산 결과 대사 Step을 추가한다.

```text
정산 원천 적재
→ 정산 계산
→ 정산 계산 결과 대사
→ 배치 완료
→ 현재 배치 Outbox 발행
```

대사 Step은 배치에 속한 모든 settlement를 끝까지 검사하고 결과를 저장한다. 하나라도 불일치하면 결과 저장과 실패 산출물 정리를 커밋한 뒤 Step을 실패시킨다. 기존 Job Listener가 배치를 `FAILED`로 변경하며 완료 Step과 Outbox 발행 Step은 실행되지 않는다.

## 대사 규칙

정산 본체의 요약값을 기대값으로, detail 재집계값을 실제값으로 비교한다.

- `productCount`와 SALE detail 건수
- `totalAmount`와 SALE `lineAmount` 합계
- `refundAmount`와 REFUND `lineAmount` 합계의 절댓값
- `feeTotalAmount`와 전체 detail `feeAmount` 합계
- `settlementTotalAmount`와 전체 detail `lineSettlementAmount` 합계

원천 연결은 해당 settlement에 연결된 source line ID 집합과 detail의 `settlement_source_line_id` 집합을 비교한다.

- detail의 source line ID가 NULL이면 실패
- source line에 대응하는 detail이 없으면 실패
- source line에 대응하지 않는 detail이 있으면 실패
- 하나의 source line ID가 여러 detail에 사용되면 실패
- source line ID 집합과 detail 연결 ID 집합이 정확히 같아야 성공

모든 settlement를 검사한 뒤 하나라도 불일치하면 배치 대사는 실패한다. 첫 불일치에서 중단하지 않아 한 번의 실행으로 전체 실패 목록을 확인할 수 있게 한다.

## Source line과 detail 관계

`settlement_detail`에 nullable `settlement_source_line_id`를 추가한다.

- `settlement_source_line(settlement_source_line_id)`를 참조하는 FK
- `settlement_detail(settlement_source_line_id)` UNIQUE
- 신규 detail 생성 시 source line ID 필수 전달
- 활성 배치 대사에서는 NULL 연결을 허용하지 않음

컬럼을 nullable로 두는 이유는 기존 데이터를 안전하게 이관하기 위해서다. UNIQUE 제약은 PostgreSQL에서 여러 NULL을 허용하므로, 연결되지 않은 기존 행 때문에 마이그레이션이 중단되지 않는다.

정산 취소 흐름은 #642 (이슈)의 구현 범위가 아니다.

## 기존 데이터 마이그레이션

Flyway V7만 사용한다. 기존 detail은 다음 값이 모두 일치하고 양쪽에서 후보가 정확히 한 건일 때만 source line ID를 채운다.

- `settlement_id`
- `order_product_id`
- `PAID ↔ SALE`, `REFUND ↔ REFUND`
- `occurred_at`
- 원금의 절댓값

매칭이 없거나 후보가 여러 건이면 잘못 추정하지 않고 NULL로 남긴다. V7 이후 생성되는 신규 정산은 계산 코드가 source line ID를 저장하고 대사 Step이 누락을 차단한다.

V7은 source line 연결 컬럼과 제약, 계산 대사 결과 테이블을 함께 추가한다. 다른 마이그레이션 번호는 사용하지 않는다.

## 대사 결과 이력

검증할 때마다 `settlement_calculation_reconciliation`에 새 행을 추가한다. 동일 배치 재실행도 기존 결과를 수정하지 않는다.

저장 항목은 다음과 같다.

- `reconciliation_id`
- `settlement_batch_id`
- `settlement_id`
- `status`: `MATCHED`, `MISMATCHED`
- 다섯 합계의 expected/actual 쌍
- `expected_source_line_count`
- `actual_detail_count`
- `actual_linked_source_line_count`
- `failure_reason`
- `verified_at`
- `created_at`

실패 settlement는 재계산 전에 삭제되므로 대사 결과의 `settlement_id`는 FK를 걸지 않고 당시 식별값으로 보존한다. `settlement_batch_id`는 배치별 조회를 지원한다.

실패 사유에는 불일치한 필드를 모두 포함한다. 여러 불일치가 있으면 한 settlement 결과에 함께 기록한다.

## 트랜잭션과 실패 산출물 정리

대사 결과가 Step 실패와 함께 롤백되지 않도록 대사 서비스는 별도 트랜잭션에서 다음 작업을 완료한다.

1. 배치의 모든 settlement 검증
2. 모든 대사 결과 누적 저장
3. 불일치 settlement에 속한 PENDING Outbox 삭제
4. 불일치 settlement에 연결된 source line의 `settlement_id` 해제
5. 불일치 settlement와 cascade detail 삭제
6. 결과 커밋

대사 서비스가 실패 요약을 반환하면 Tasklet이 예외를 던진다. 인프라 오류로 결과 저장이나 정리가 원자적으로 끝나지 않으면 서비스 트랜잭션 전체를 롤백하고 Job을 실패시킨다.

일치한 settlement와 PENDING Outbox는 유지한다.

## 동일 배치 재실행

대사 실패 후 재실행에서는 완료됐던 계산 Step을 다시 시작할 수 있게 설정한다. 앞선 정리에서 불일치 source line만 미정산 상태로 돌아가므로 계산 Step은 해당 판매자만 다시 계산한다.

계산 Step 자체가 중간에 실패한 기존 동작은 유지한다. 이 경우 이미 커밋된 source line은 연결 상태이므로 남은 source line만 처리한다.

재실행 후 대사 Step은 배치 전체를 다시 검사하고 새로운 결과 행을 추가한다. 최신 실행이 모두 일치하면 배치를 완료한다.

## Outbox 발행 방어

Outbox에 새 상태는 추가하지 않는다.

- 계산 시 기존처럼 `PENDING` 이벤트 생성
- 계산 대사 성공 후에만 배치 `COMPLETED`
- 이전 이벤트 재시도 조회는 `COMPLETED` 배치만 반환
- 현재 배치 flush 조회도 `COMPLETED` 배치만 반환

따라서 대사 실패 배치의 PENDING Outbox는 자동 발행되지 않는다. Outbox 조회 쿼리와 관련 테스트는 별도 커밋으로 분리한다.

## 테스트

도메인 단위 테스트는 정상 대사, 다섯 합계 각각의 불일치, detail 누락, source line 중복 연결, NULL 연결을 검증한다.

애플리케이션 테스트는 전체 settlement 검사, 결과 누적, 불일치 settlement만 정리, 실패 결과 보존을 검증한다.

배치 통합 테스트는 성공 시에만 배치 완료, 실패 시 후속 Step 중단, Outbox 미발행, 동일 배치 재실행 시 불일치 settlement 재계산을 검증한다.

영속성·마이그레이션 테스트는 FK, UNIQUE, 여러 NULL 허용, 명확한 기존 데이터만 backfill, 실패 배치 Outbox 조회 차단을 검증한다.

## 다른 이슈와의 통합 지점

- #639 (이슈)가 공통 대사 실패 상태를 추가하면 대사 Tasklet 실패를 기존 `FAILED` 대신 그 상태에 연결한다.
- #637 (이슈)는 `COMPLETED` 이후 Kafka 발행과 User Service 반영 대사를 담당하며 이 설계의 Outbox 상태나 소비 결과를 확장하지 않는다.
