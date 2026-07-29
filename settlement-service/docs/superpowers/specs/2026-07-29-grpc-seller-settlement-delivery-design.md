# gRPC 기반 Seller Settlement 전달 및 대사 설계

- 작성일: 2026-07-29
- 대상 이슈: #637
- 관련 이슈: #635, #639, #642, #655
- 작업 브랜치: `feat/#637-grpc-settlement-delivery-reconciliation`

## 1. 배경

현재 Settlement Service는 정산 계산 결과와 Outbox 이벤트를 같은 트랜잭션에서 저장하고, 배치 후반부에 Kafka로 `SETTLEMENT_CREATED` 이벤트를 발행한다. User Service는 이벤트를 소비해 `seller_settlement`와 상세를 생성한다.

이 구조에서 Outbox의 `PUBLISHED`는 Kafka 브로커 발행 성공까지만 의미한다. User Service의 실제 저장 완료와 저장 결과의 일치 여부를 확인하려면 Consumer 처리 이력, DLT, 완료 이벤트 또는 별도 조회 포트까지 운영해야 한다.

#637은 이 비동기 전달 경로를 정산 단위 동기 gRPC 호출로 교체한다. 정산 계산 트랜잭션과 원격 저장 트랜잭션을 하나로 묶지는 않는다. 대신 정산 단위 Delivery 원장, 안정적인 요청 식별자, User Service의 멱등 처리와 저장 결과 응답을 조합해 실패 격리와 추적성을 확보한다.

## 2. 목표

- Settlement 한 건과 상세 전체를 User Service에 gRPC로 전달한다.
- 한 정산의 전달 실패가 다음 정산 계산 또는 전달을 막지 않게 한다.
- 응답 유실 후에도 같은 요청을 안전하게 재호출한다.
- User Service가 실제 저장한 데이터를 응답하고 Settlement Service가 원본과 직접 비교한다.
- 배치 실행 상태와 정산 단위 전달·대사 상태를 분리한다.
- Kafka Outbox, 정산 Consumer와 DLT 운영 경로를 제거한다.
- 전달 실패와 불일치 사유를 조회 가능한 데이터로 남긴다.

## 3. 제외 범위

- Order와 `settlement_source_line` 대사: #639
- 정산 계산 합계와 SourceLine에서 Detail까지의 추적: #642
- 어드민 실패 목록, 재전송 버튼과 단발성 Kubernetes Job: #655
- 별도 주기적 재전달 CronJob
- 자동 데이터 보정 또는 기존 Seller Settlement 덮어쓰기
- 상세 해시, mismatch 전용 테이블, 전달 시도 이력 테이블
- 스트리밍 또는 분할 gRPC 전송

## 4. 검토한 대안

### 4.1 Kafka Outbox 유지와 gRPC 대사 조회 추가

Kafka의 비동기 확장성과 장애 격리를 유지할 수 있다. 반면 현재 요구사항을 충족하려면 Outbox, Consumer 처리 이력, DLT, 완료 확인과 대사 조회를 모두 운영해야 한다. 정산 CronJob의 건별 즉시 반영 요구에 비해 운영 경로가 많아 제외한다.

### 4.2 gRPC 등록 명령과 대사 조회 RPC 분리

등록과 조회 책임이 명확하지만, 한 정산을 처리할 때 원격 호출이 두 번 필요하다. User Service가 등록 트랜잭션 커밋 후 실제 저장 결과를 다시 읽어 같은 응답에 담을 수 있으므로 별도 조회 RPC는 추가하지 않는다.

### 4.3 단일 gRPC 등록과 저장 결과 응답

`RegisterSellerSettlement` 한 번으로 등록, 멱등 처리와 저장 결과 확인을 수행한다. 정산 단위 호출과 Delivery 원장으로 실패를 격리할 수 있고 계약과 운영 경로가 가장 단순하다. 이 방식을 선택한다.

## 5. 전체 흐름

```text
정산 원천 적재 및 내부 대사
  → Settlement 계산
  → Settlement + SettlementDelivery 원자적 저장
  → 계산 결과 대사
  → SettlementBatch COMPLETED
  → CALCULATED Delivery를 Settlement별로 순차 처리
      → 시도 정보 저장
      → RegisterSellerSettlement gRPC 호출
      → User Service 저장 트랜잭션 커밋
      → User Service DB 재조회
      → 저장된 Snapshot 응답
      → Settlement 원본과 필드별 비교
      → RECONCILED 또는 MISMATCH
    통신 실패
      → 최대 2회 재시도
      → 최종 실패 시 DELIVERY_FAILED
      → 다음 Settlement 계속 처리
```

Settlement 계산이 완료되면 `SettlementBatchStatus`는 `COMPLETED`가 된다. gRPC 실패는 계산 배치를 실패시키지 않는다. 전달 결과는 `settlement_delivery`에서 별도로 집계한다.

로컬 Delivery 저장 자체가 실패하는 등 결과를 기록할 수 없는 로컬 장애는 Job 실패로 처리한다. 원격 통신 실패와 대사 불일치는 정산 단위로 흡수한다.

배치가 `COMPLETED`된 뒤 Delivery Step에서 로컬 장애가 발생하면 Spring Batch Job은 실패할 수 있지만 도메인 배치 상태는 계산 완료를 의미하므로 되돌리지 않는다. 같은 Job을 재시작하면 현재 배치의 `CALCULATED` Delivery만 다시 처리한다. `RECONCILED`, `MISMATCH`, `DELIVERY_FAILED`는 자동 재처리하지 않는다.

## 6. Settlement Delivery 모델

Settlement와 Delivery는 같은 계산 트랜잭션에서 저장한다. 원격 호출은 이 트랜잭션이 커밋된 뒤 실행한다.

### 6.1 상태

| 상태 | 의미 |
| --- | --- |
| `CALCULATED` | 정산 계산과 Delivery 생성 완료, 아직 대사 완료 전 |
| `RECONCILED` | User Service 저장 결과가 원본과 일치 |
| `DELIVERY_FAILED` | 재시도 가능한 호출을 모두 소진했거나 재시도 불가능한 gRPC 오류 발생 |
| `MISMATCH` | User Service가 반환한 저장 결과가 원본과 불일치 |

`SELLER_SEEDED` 중간 상태는 두지 않는다. User Service의 저장과 read-back이 하나의 RPC 안에서 끝나며, Settlement Service는 응답을 받은 즉시 대사를 수행하기 때문이다.

### 6.2 저장 필드

`settlement_delivery`는 다음 정보를 가진다.

| 필드 | 제약과 의미 |
| --- | --- |
| `settlement_delivery_id` | UUID 기본키 |
| `delivery_request_id` | UUID, NOT NULL, UNIQUE. 최초 생성 후 변경하지 않음 |
| `settlement_id` | UUID, NOT NULL, UNIQUE, Settlement 참조 |
| `settlement_batch_id` | UUID, NOT NULL. 배치별 집계 기준 |
| `status` | 네 가지 Delivery 상태 |
| `attempt_count` | 전체 누적 호출 시도 횟수, 기본값 0 |
| `status_reason` | TEXT. 실패 또는 불일치 사유, 정상 상태에서는 NULL |
| `first_attempt_at` | 첫 호출 시각 |
| `last_attempt_at` | 최근 호출 시각 |
| `reconciled_at` | 대사 완료 시각 |
| `created_at`, `updated_at` | 생성·변경 시각 |

별도 mismatch 테이블과 mismatch enum은 만들지 않는다. 불일치가 여러 건이면 고정된 비교 순서에서 처음 발견한 필드의 기대값과 실제값, 전체 불일치 건수를 `status_reason`에 요약한다.

예시는 다음과 같다.

```text
feeTotalAmount 불일치: expected=3000.00, actual=2500.00, mismatchCount=1
settlementDetailId=... feeRate 불일치: expected=0.0300, actual=0.0200, mismatchCount=3
gRPC DEADLINE_EXCEEDED: deadline=10s, attempts=3
```

## 7. gRPC 계약

기존 `SellerSettlementQueryService`는 AI의 판매자 정산 조회용이며 AI 토큰과 `x-user-id`를 사용한다. 정산 등록은 인증 주체와 책임이 다르므로 별도 Command Service로 분리한다.

공용 계약은 `grpc/user/seller_settlement_command.proto`에 둔다.

```protobuf
service SellerSettlementCommandService {
  rpc RegisterSellerSettlement(RegisterSellerSettlementRequest)
      returns (RegisterSellerSettlementResponse);
}

message RegisterSellerSettlementRequest {
  string delivery_request_id = 1;
  SellerSettlementSnapshot settlement = 2;
}

message RegisterSellerSettlementResponse {
  SellerSettlementSnapshot stored_settlement = 1;
}

message SellerSettlementSnapshot {
  optional string delivery_request_id = 1;
  // settlement 본체와 상세 필드
}
```

`SellerSettlementSnapshot`은 다음 정보를 포함한다.

- `delivery_request_id`
- `settlement_id`
- `seller_id`
- `period_start`, `period_end`
- `product_count`
- `gross_sales_amount`
- `refund_amount`
- `fee_total_amount`
- `settlement_total_amount`
- `calculated_at`
- 상세 전체

요청 최상위의 `delivery_request_id`는 User Service 검증에서 필수다. 응답 Snapshot의 `delivery_request_id`는 기존 Kafka 행의 NULL을 표현할 수 있도록 `optional`로 둔다. 기존 행과 요청이 일치해 요청 ID를 연결한 경우에는 값이 채워진 상태로 응답한다.

상세는 다음 정보를 포함한다.

- `settlement_detail_id`
- `order_product_id`
- `line_type`
- `line_amount`
- `fee_rate`
- `fee_amount`
- `line_settlement_amount`
- `occurred_at`

UUID, 날짜, 시각과 `BigDecimal`은 기존 공용 proto 관례에 맞춰 문자열로 표현한다. 날짜는 ISO-8601 `yyyy-MM-dd`, 시각은 ISO-8601 문자열로 고정한다. 금액과 수수료율은 문자열로 전달해 정밀도 손실을 방지한다.

계약에서는 기존 `totalAmount`의 실제 의미가 드러나도록 `gross_sales_amount`를 사용한다. 이번 이슈에서 기존 Java 필드와 DB의 `total_amount`는 변경하지 않고 매퍼에서 변환한다.

#642가 `settlement_detail`에 SourceLine 식별자를 추가하면 기존 필드 번호를 바꾸지 않고 신규 proto 필드로 통합한다.

## 8. User Service 등록과 멱등성

### 8.1 신규 등록

1. UUID, 날짜, 시각, 금액과 필수 필드를 검증한다.
2. `deliveryRequestId`와 `settlementId`로 기존 행을 조회한다.
3. 신규 요청이면 Seller Settlement와 상세를 하나의 트랜잭션에서 저장한다.
4. 저장 트랜잭션을 커밋한다.
5. 별도 read-only 트랜잭션에서 본체와 상세를 다시 조회한다.
6. 조회 결과로 `RegisterSellerSettlementResponse`를 만든다.

저장 명령과 read-back 조회는 서로 다른 Spring Bean 또는 명시적인 트랜잭션 경계를 사용한다. 같은 영속성 컨텍스트의 요청 객체를 그대로 응답하지 않는다.

### 8.2 재호출

같은 `deliveryRequestId`가 다시 들어오면 새 행을 만들지 않는다. 기존 Seller Settlement를 DB에서 조회해 반환한다. 클라이언트가 timeout을 받았지만 User Service 커밋이 성공한 경우에도 같은 규칙이 적용된다.

같은 `settlementId`에 이미 다른 데이터나 다른 요청 ID가 연결돼 있으면 기존 데이터를 덮어쓰지 않는다. 기존 저장 Snapshot을 반환하고 Settlement Service가 요청 원본과 비교해 `MISMATCH`로 기록한다.

### 8.3 기존 Kafka 데이터

User Service V4에서 `seller_settlement.delivery_request_id`를 nullable UUID로 추가하고 UNIQUE 제약을 적용한다.

- 기존 Kafka 행: `delivery_request_id = NULL`
- 신규 gRPC 행: 애플리케이션에서 반드시 값 저장
- 기존 행과 신규 요청의 모든 정산 필드와 상세가 일치: 기존 행에 `deliveryRequestId`만 연결
- 하나라도 불일치: 기존 행을 변경하지 않고 기존 Snapshot 반환

기존 데이터 보존을 위해 DB 컬럼 자체에는 NOT NULL을 적용하지 않는다.

## 9. Settlement Service 호출과 재시도

Settlement 한 건과 해당 상세 전체를 unary gRPC 한 번으로 전달한다. 여러 Settlement는 순차 처리한다.

호출 전에 짧은 로컬 트랜잭션으로 `attempt_count`, `first_attempt_at`, `last_attempt_at`을 기록하고 커밋한다. gRPC 호출 중에는 로컬 DB 트랜잭션을 열어두지 않는다.

일반 실행과 Spring Batch 재시작은 현재 배치의 `CALCULATED` Delivery만 후보로 조회한다. 예상된 통신 실패로 `DELIVERY_FAILED`가 된 행은 다음 주간 Job에서 자동 재전달하지 않는다. 프로세스가 응답 직후 종료돼 로컬 상태가 `CALCULATED`로 남은 경우에는 같은 `deliveryRequestId`로 재호출해 User Service의 기존 저장 결과를 돌려받는다.

### 9.1 정책

- 호출당 deadline: 10초
- 최초 호출: 1회
- 최대 재시도: 2회
- 재시도 간격: 1초, 3초
- 최대 시도 횟수: 3회
- 회로 차단: 사용하지 않음
- 다음 주간 Job의 자동 재전달: 사용하지 않음

### 9.2 오류 분류

| gRPC 결과 | 처리 |
| --- | --- |
| `UNAVAILABLE` | 최대 2회 재시도 |
| `DEADLINE_EXCEEDED` | 최대 2회 재시도 |
| `RESOURCE_EXHAUSTED` | 재시도 없이 `DELIVERY_FAILED` |
| `UNAUTHENTICATED`, `PERMISSION_DENIED` | 재시도 없이 `DELIVERY_FAILED` |
| `INVALID_ARGUMENT`, `FAILED_PRECONDITION` | 재시도 없이 `DELIVERY_FAILED` |
| 정상 응답, 데이터 일치 | `RECONCILED` |
| 정상 응답, 데이터 불일치 | `MISMATCH` |

모든 재시도는 동일한 `deliveryRequestId`를 사용한다. 한 정산이 최종 실패해도 다음 Delivery 처리를 계속한다.

gRPC Java 1.80.0의 기본 수신 메시지 제한 4MiB를 그대로 사용한다. 요청은 User Service 서버의 수신 제한, 응답은 Settlement Service 클라이언트의 수신 제한을 적용받는다. 크기 초과는 `RESOURCE_EXHAUSTED`로 기록하고 분할 또는 스트리밍 전송은 이번 범위에서 추가하지 않는다.

## 10. 직접 대사 규칙

Settlement Service는 User Service가 read-back한 Snapshot을 원본과 비교한다.

- UUID와 enum: 정확히 일치
- 날짜: 정확히 일치
- 시각: 마이크로초 단위로 정규화 후 일치
- 금액과 수수료율: `BigDecimal.compareTo` 기준으로 비교해 scale 차이는 무시
- 상세 순서: 무시
- 상세 매칭 키: `settlementDetailId`
- 상세 ID 중복: 불일치
- 한쪽에만 존재하는 상세: 불일치
- 매칭된 상세의 모든 계약 필드: 직접 비교

`deliveryRequestId`도 응답 Snapshot과 비교한다. 기존 행에 다른 요청 ID가 연결돼 있으면 데이터가 같더라도 `MISMATCH`다.

정해진 순서로 모든 필드를 비교해 전체 불일치 건수를 계산한다. `status_reason`에는 첫 번째 불일치와 전체 건수를 저장한다. 성공 시 `status_reason`을 비우고 `reconciled_at`을 기록한다.

## 11. 인증과 운영 설정

Settlement 등록은 사용자 행위가 아닌 서비스 간 시스템 명령이다.

- metadata: `x-internal-service-token`
- 호출자: Settlement Service
- `x-user-id`: 사용하지 않음
- 토큰: AI Service 토큰과 분리된 Settlement Service 전용 값
- 보관: Kubernetes Secret
- 로그와 Job 인자에 토큰을 출력하지 않음

User Service는 Query Service와 Command Service의 인증 규칙을 분리한다. Settlement CronJob은 User Service gRPC 주소와 전용 토큰을 환경 설정으로 주입받는다.

공용 proto와 Kubernetes 설정은 저장소 루트 공용 영역에 변경한다. 사용자가 #637 범위의 변경을 승인했다. #655의 어드민 Retry Job과 RBAC는 이번 변경에 포함하지 않는다.

## 12. 마이그레이션과 제거 범위

### 12.1 Settlement Service V8

- `settlement_delivery` 생성
- `settlement_outbox_event` 삭제

Outbox 데이터 삭제는 사용자가 승인했다.

### 12.2 User Service V4

- `seller_settlement.delivery_request_id UUID NULL` 추가
- UNIQUE 제약 또는 유니크 인덱스 추가

### 12.3 코드 제거

- Settlement Service Outbox 도메인, 포트, 저장 어댑터
- Outbox 생성, 발행, 재시도 Application Service와 Batch Step
- 정산 Kafka Producer와 서비스 전용 설정
- User Service 정산 Kafka Consumer
- 정산 이벤트 V1/V2 DTO와 Consumer 전용 검증
- 정산 전용 DLT 처리와 설정

다른 도메인이 사용하는 공용 `EventMessage`와 Kafka 공통 기반은 제거하지 않는다.

## 13. 관측과 배치 집계

`settlement_delivery`를 기준으로 배치별 다음 값을 집계한다.

- 전체 Delivery
- `CALCULATED`
- `RECONCILED`
- `DELIVERY_FAILED`
- `MISMATCH`

Delivery Step 종료 시 현재 배치의 상태별 건수를 집계해 구조화 로그로 남긴다. 별도 배치 집계 테이블이나 `SettlementBatchStatus` 컬럼은 추가하지 않는다. #655의 어드민 조회는 같은 Delivery 데이터를 기준으로 집계한다.

호출 시도와 실패는 `settlementId`, `deliveryRequestId`, 시도 번호, gRPC Status와 소요 시간을 포함한 구조화 로그로 남긴다. 토큰과 상세 전체는 로그에 남기지 않는다.

별도 시도 이력 테이블은 만들지 않는다. 누적 횟수와 최초·최근 시각, 최신 사유만 Delivery에 저장한다.

## 14. 테스트 전략

핵심 상태 전이, 금액, 중복과 실패 격리는 테스트 우선으로 구현한다.

### 14.1 Settlement Service

- Settlement와 Delivery의 원자적 생성
- 정상 응답 후 `RECONCILED`
- 본체 금액·건수·기간 불일치 후 `MISMATCH`
- 상세 누락·중복·필드 불일치 후 `MISMATCH`
- 소수점 scale과 상세 순서 차이는 일치 처리
- `UNAVAILABLE`, `DEADLINE_EXCEEDED` 재시도
- 총 3회 소진 후 `DELIVERY_FAILED`
- 인증·검증·크기 오류 즉시 실패
- 한 건 실패 후 다음 Settlement 계속 처리
- 배치 `COMPLETED`와 Delivery 실패 상태 분리
- 배치별 상태 집계

### 14.2 User Service

- 신규 요청 저장과 커밋 후 read-back
- 같은 `deliveryRequestId` 중복 호출 시 기존 결과 반환
- 같은 `settlementId` 중복 생성 방지
- 기존 nullable 행과 요청이 일치할 때 요청 ID 연결
- 기존 행이 불일치할 때 덮어쓰기 차단
- 전용 토큰 성공, 누락·오류 토큰 거부
- `x-user-id` 없이 Command RPC 호출

### 14.3 통합

- 실제 proto 기반 요청·응답 매핑
- 응답 유실을 가정한 동일 요청 재호출
- V8에서 Outbox 삭제와 Delivery 생성
- V4에서 기존 Seller Settlement 보존
- 기본 4MiB 초과 오류 분류

## 15. 병합과 충돌 관리

현재 열린 PR의 Flyway 예약은 다음과 같다.

- #649: Settlement V6
- #651: Settlement V7
- #637: Settlement V8, User V4

마이그레이션 번호는 충돌하지 않는다. 공유 DB에는 V6, V7, V8 순서로 적용돼야 한다. #637 최종 병합 전에 #649와 #651 변경을 기준 브랜치에 반영한다.

#651은 계산 결과 대사 과정에서 Outbox 발행 제어 코드를 수정한다. #637은 최종적으로 Outbox를 제거하므로 리베이스 시 해당 변경을 Delivery 실행 순서로 치환하고 #642의 계산 대사가 성공한 뒤에만 배치를 완료하고 전달하도록 통합한다.

## 16. 완료 조건

- Kafka Outbox 없이 정산 단위 gRPC 전달이 동작한다.
- User Service가 같은 `deliveryRequestId`를 중복 저장하지 않는다.
- User Service의 실제 저장 결과가 한 RPC 응답으로 반환된다.
- Settlement Service가 전체 필드를 직접 비교해 최종 상태를 저장한다.
- 한 정산의 전달 실패가 다음 정산 처리를 중단하지 않는다.
- 배치 상태와 Delivery 상태가 독립적으로 집계된다.
- Settlement V8과 User V4 마이그레이션 테스트가 통과한다.
- Settlement Service와 User Service 관련 테스트가 통과한다.
- #639, #642, #655 범위를 구현하지 않는다.
