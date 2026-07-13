# 주문 데이터 수급 방식

정산은 한 기간의 정산 대상 주문 라인 — 그 기간에 결제 확정된 order_product, 그리고 그 기간에
환불된 order_product — 이 필요하다. 이 데이터는 order 서비스가 소유하므로, 서비스 경계를 넘어
어떻게 가져올지가 문제가 된다.

## 결정 히스토리

이 문서의 결론은 두 번 바뀌었다. 경위를 남겨 둔다.

1. **테이블 직접 조회** — 초기엔 `order_product` 를 `@Subselect`/`@Immutable` 뷰로 직접 읽었다.
   공유 DB 전제라 서비스 분리 시 끊어야 할 결합이었다.
2. **Kafka push(이벤트)** — 서비스 분리를 대비해 order 가 `ORDER_PAID`/`ORDER_REFUNDED` 를 발행하고
   정산이 컨슈머로 로컬에 사본을 쌓는 방식으로 한 번 구현했다.
3. **gRPC pull(현재 결정)** — 배포를 k8s 로 옮기며 배치를 CronJob 으로 분리하기로 하면서, 상시
   컨슈머를 없애고 **배치 시점에 order 를 gRPC 로 당겨오는** 방식으로 되돌린다. 이 문서가 정하는 방향이다.

## 지금 구조(전환 대상)

- `SettlementSourceRepository` 가 정산이 주문 데이터를 가져오는 유일한 통로다. (이 포트는 유지된다.)
- 원천은 정산이 소유하는 로컬 테이블 `settlement_source_line` 에 쌓는다. (이 테이블도 유지된다.)
- 지금은 이 테이블을 **Kafka 컨슈머(`OrderEventConsumer`)가 실시간으로 채운다.** 이 채우는 경로를
  gRPC pull 로 교체하는 것이 이번 전환의 골자다. **`settlement_source_line` 과 배치 계산부는 그대로다.**

## 왜 pull 로 되돌리나

선택 기준은 **데이터의 성격**이지, 누가 실행을 트리거했는지가 아니다. 정산 데이터의 성격을 보면
pull 이 더 맞는다.

- **정산은 월 마감 도메인이다.** 실시간 즉시성이 필요 없다. 배치가 도는 시점에 한 번, 그 기간의
  확정 데이터를 당겨오면 충분하다. Kafka 의 실시간성은 정산에서 쓰이지 않는 이점이었다.
- **k8s CronJob 아키텍처와 정합한다.** 배치를 CronJob 으로 분리하면 상시 프로세스는 API 서버뿐이다.
  컨슈머를 없애면 "배치는 CronJob, 상시부는 API 만"으로 깔끔해진다.
  (배포 분리 배경은 `deployment-ci-cd.md` 참고.)
- **Kafka 운영 복잡도가 통째로 사라진다.** push 는 멱등 upsert·순서·재처리·오프셋·dead-letter·백필,
  그리고 **order 쪽 transactional outbox** 를 전제로 했다. outbox 가 없으면 정산이 라인을 조용히 놓쳐
  과소 정산할 수 있었다. pull 은 배치가 기간 단위로 전량을 훑으므로 이 전제가 모두 없어진다.
- **완전성(누락 없음)에 강하다.** push 는 이벤트 유실 시 조용히 누락되지만, pull 은 order 를 진실의
  원천으로 그 기간 전체를 조회하므로 누락에 강하다.
- **소유는 유지된다.** gRPC 로 받은 걸 `settlement_source_line` 에 박제하므로, 감사·재현·dedup(이미
  정산된 라인 제외)은 그대로 살아 있다. "당겨와서 즉석 계산 후 폐기"가 아니라 "당겨와서 소유"다.

pull 의 대가는 **배치 시점에 order 가 떠 있어야 한다**는 것과 **대량 조회를 페이징/스트리밍으로**
받아야 한다는 것이다. 월 배치라 order 일시 장애는 재시도로 흡수되고, 스트리밍으로 양은 감당한다.

## 선택지 (비교)

| 방식 | 장점 | 단점 |
| --- | --- | --- |
| 테이블 직접 조회 | 가장 단순; 항상 최신; 추가 인프라 없음 | order 스키마에 결합; 공유 DB 필요; 서비스 분리 후엔 불가 |
| Kafka push | order 가용성과 무관; 독립 배포 | 로컬 사본 + 중복/순서/재처리; outbox 전제; 최종 일관성; 상시 컨슈머 필요 |
| **gRPC pull (선택)** | order 가 진실의 원천; 상시 컨슈머 불필요(CronJob 정합); outbox·DLT·백필 불필요; 완전성 강함; 소유 유지 | 배치 시점 order 가용성 필요; 대량은 페이징/스트리밍 |

## gRPC pull 설계 (선택한 방향)

### 핵심 원리 — 상태(status)가 아니라 시각(timestamp)으로 조회한다

`order_product` 는 결제/환불을 **상태 enum 을 in-place 로 바꿔** 표현한다(`PAID` → `REFUNDED`).
그래서 "지금 status 가 뭐냐"로 정산 대상을 고르면 시점 귀속이 깨진다 — 6월에 결제돼 6월 정산에
들어간 라인이 7월에 환불되면, 배치 시점 status 는 이미 `REFUNDED` 라 6월 결제 사실이 사라진다.

대신 **발생 시각 두 개(`paidAt`·`refundedAt`)를 각각 기간 필터로** 조회한다. status 는 보지 않는다.
timestamp 는 불변이라, 상태가 in-place 로 바뀌어도 각 사건이 자기 기간에 정확히 귀속된다.
사실상 `paidAt` = PAID 사건, `refundedAt` = REFUND 사건을 두 컬럼으로 근사하는 것이다.

> **전제(order 팀 합의됨):** `order_product` 가 `paidAt`(결제 확정 시각)·`refundedAt`(환불 시각)을
> 갖고, gRPC 응답으로 넘겨준다. `refundedAt` 은 이미 있고, `paidAt` 은 order 가 추가한다.

기간 P 를 정산할 때 order 에 두 종류를 요청한다.

```
결제 라인:  WHERE paidAt     BETWEEN P.start AND P.end   → PAID   SourceLine 으로 적재
환불 라인:  WHERE refundedAt BETWEEN P.start AND P.end   → REFUND SourceLine 으로 적재(음수)
```

### 케이스 검증 (7월 배치 기준)

| 상황 | paidAt | refundedAt | 결제쿼리(7월) | 환불쿼리(7월) | 7월 정산 반영 |
| --- | --- | --- | --- | --- | --- |
| 7월 결제, 유효 | 7월 | 없음 | ✅ | ✗ | +결제 |
| 7월 결제 + 7월 환불 | 7월 | 7월 | ✅ | ✅ | +결제 −환불 = 상계 |
| 6월 결제(이미 정산) + 7월 환불 | 6월 | 7월 | ✗ | ✅ | −환불 보정만 |
| 7월 결제, 8월 환불 | 7월 | 없음(아직) | ✅ | ✗ | +결제 (환불은 8월에) |

세 번째가 "정산 후 환불"이다. 6월 결제분은 `paidAt=6월` 이라 7월 결제쿼리에 안 걸리고,
`refundedAt=7월` 이라 환불쿼리에만 걸린다. **이미 확정된 6월 정산은 건드리지 않고, 7월에 −환불
보정 라인만 추가**한다. 확정 정산을 소급 수정하지 않는 것이 회계 원칙이며, `settlement_source_line`
이 PAID/REFUND 를 별도 라인으로 쌓는 구조라 이 보정 방식을 그대로 지원한다.

### 멱등 — "환불로 변한 것"을 별도 라인으로 잡는다

push 시절 멱등키는 `nameUUIDFromBytes(주문eventId | orderProductId | 상태)` 였다. pull 은 이벤트가
없어 `주문eventId` 가 사라지므로, **`orderProductId` + 상태**로 seed 를 재구성한다.

```
PAID   라인 멱등키 = nameUUIDFromBytes(orderProductId | "PAID")
REFUND 라인 멱등키 = nameUUIDFromBytes(orderProductId | "REFUND")
```

- 한 order_product 는 결제 1회·환불 1회라(`OrderProduct.refund()` 는 전액 1회) 이 seed 로 라인이
  유일해진다. 부분·다중 환불이 생기면 order 가 `refundId` 를 함께 주고 seed 에 더한다.
- **환불 체크의 핵심:** 같은 `orderProductId` 가 이미 PAID 라인으로 적재돼 있어도, REFUND 는 상태가
  달라(`...|PAID` vs `...|REFUND`) **PAID 를 덮어쓰지 않고 별도 REFUND 라인으로 적재**된다. 배치는
  PAID 합에서 REFUND 합을 빼 순액을 낸다. "환불로 변한 것"이 결제 라인을 지우는 게 아니라 음수
  보정 라인으로 더해지는 것 — 이게 시점 귀속과 감사 추적을 동시에 지킨다.
- 배치 재실행 시 같은 기간을 재조회해도 같은 멱등키라 `existsByEventId` 로 걸러 중복 적재를 막는다.
  (지금의 `SettlementSourceApplicationService` 멱등 로직을 seed 만 바꿔 그대로 쓴다.)
- `settlement_source_line.event_id`(단일 unique) 스키마·도메인 제약은 그대로 둔다.

### 음수 정산 (정책 결정 대상 — 여기선 존재만 명시)

"6월 결제(이미 정산) + 7월 환불"에서, 7월에 그 판매자의 다른 결제가 환불액보다 적으면 **7월 정산
순액이 음수**가 될 수 있다. 이를 다음 달로 이월할지, 마이너스로 확정할지는 **정산 정책 결정**이며
전송 방식(push/pull)과 무관하다. **이월(carry-forward)로 결정**했으며, 처리 규칙은
`negative-settlement-carryforward.md` 에 정리한다.

### 배치 흐름 — 앞단에 pull 스텝 하나 추가

```
[Step 0] order gRPC pull → settlement_source_line upsert   ← 신규(이 전환의 유일한 추가)
   ↓
[Step 1] 정산 생성  (CreateSettlementBatchTasklet)          ← 그대로
[Step 2] 계산       (SettlementTargetReader→Processor→Writer) ← 그대로
[Step 3] 완료       (CompleteSettlementBatchTasklet)         ← 그대로
```

Step 0 가 gRPC 로 그 기간의 결제·환불 라인을 당겨 `settlement_source_line` 에 멱등 적재한다.
이후 스텝은 지금과 동일하게 로컬 테이블만 읽는다.

### order 가 제공할 gRPC 계약 (정산이 제안, order 팀이 서버 구현)

period 하나로 결제·환불 라인을 `line_type` 으로 구분해 한 응답(`repeated`)으로 받는다.

> **전송 방식은 unary + `repeated` 로 구현했다(#260).** 초안은 대량 대응 server-streaming 이었으나
> 월 배치 1회 호출·구현 단순성을 우선했다. 볼륨이 실제 문제가 되면 streaming 으로 전환한다.
> 실제 커밋된 계약은 `grpc/order/order_query.proto`(루트 공유) 이며 아래와 같다.

```protobuf
service OrderQueryService {
  rpc GetSettleableLines(GetSettleableLinesRequest) returns (GetSettleableLinesResponse);
}

message GetSettleableLinesRequest {
  string period = 1;   // "2026-06" — paidAt/refundedAt '발생 시각' 기준 기간
}

message GetSettleableLinesResponse {
  repeated SettleableLine lines = 1;
}

message SettleableLine {
  string line_type        = 1;   // PAID | REFUND
  string order_id         = 2;
  string order_product_id = 3;
  string seller_id        = 4;
  int64  line_amount      = 5;   // PAID 는 결제액, REFUND 는 환불액(양수)
  string occurred_at      = 6;   // PAID 면 paidAt, REFUND 면 refundedAt
}
```

- 서버는 내부적으로 `paidAt ∈ P`(결제 라인)과 `refundedAt ∈ P`(환불 라인) 두 쿼리를 합쳐 내려준다.
- **status 로 필터하지 않는다.** 환불된 라인도 `paidAt` 이 P 안이면 결제 라인으로, `refundedAt` 이
  P 안이면 환불 라인으로 각각 내려온다.
- 멱등키 `event_id` 는 order 가 주지 않는다. 정산이 `(order_product_id + line_type)` 로 로컬 파생한다.

### 코드에서 바뀌는 것 (구현 결과 — #260)

- **신규:** `infrastructure/client/order/`(`OrderSettlementQueryClient` + `config/OrderGrpcClientConfig`),
  `application/port/OrderSettlementQueryPort`, `application/dto/SettleableLine`,
  `application/usecase/LoadSettlementSourceUseCase`, `grpc/order/order_query.proto`(루트 공유),
  배치 첫 스텝 `LoadSettlementSourceTasklet`(`loadSettlementSourceStep`).
- **변경:** `SettlementSourceApplicationService` — 이벤트 핸들러(`recordOrderPaid`/`recordOrderRefunded`)는
  **남겨두고**, `LoadSettlementSourceUseCase.load(period)` 를 추가로 구현(gRPC pull → bulk 적재). 멱등키
  seed 는 pull 경로용으로 `orderProductId | eventType`(`pullLineEventId`)을 새로 둔다.
  `SettlementSourceRepository` 에 `saveAll`·`findExistingEventIds` 추가.
- **유지(제거는 order 서버 가동 후):** `infrastructure/messaging/kafka/consumer/order/*`
  (`OrderEventConsumer`·`OrderEventType`), order 이벤트 DTO 는 남겨두고 `settlement.kafka.listener.order.enabled: false`
  로 비활성. order gRPC 서버가 아직 없어 유일하게 계약이 있는 폴백이라, 서버 가동 후 정리한다.
- **그대로:** `settlement_source_line`(`SettlementSourceLine`), `SettlementSourceRepository` 조회 계약,
  `findSettleableLines`, 계산 유스케이스, 배치 뒷단계 전부.

포트(`application/port`)·어댑터(`infrastructure/client`) 분리 덕에 안쪽 계층은 전송이 gRPC 로 바뀐
걸 모른다. (포트·어댑터 규칙은 `clean-architecture.md` §4, gRPC 선택 근거는 `internal-sync-transport.md`.)

## order 팀 합의 사항

1. **`order_product.paidAt` 추가** — 결제 확정 시각 전용 컬럼. `updatedAt` 은 다운로드·환불 등으로도
   갱신돼 결제 귀속에 못 쓴다. (`refundedAt` 은 이미 있다.)
2. **`GetSettleableLines(period)` gRPC 서버 신설** — `paidAt`/`refundedAt` 시각 기준으로 결제·환불
   라인을 스트리밍. status 스냅샷이 아니라 시각 기준이어야 한다.
3. **기존 이벤트 발행 정리** — order 의 `ORDER_PAID`/`ORDER_REFUNDED` 발행은 정산이 더 이상 구독하지
   않는다. 다른 구독자가 없으면 정리 대상이나, 이는 order 팀 판단이다.
</content>
</invoke>
