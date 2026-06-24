# 주문 데이터 수급 방식

정산은 한 기간의 정산 대상 주문 라인 — PAID 상태이면서 아직 정산되지 않은 order_product —
이 필요하다. 지금은 이 데이터를 주문 테이블에서 직접 읽는다. 서비스를 분리하면 이 읽기가
서비스 경계를 넘어야 하므로, 어떻게 가져올지가 문제가 된다.

## 지금 구조

- `SettlementSourceRepository`가 정산이 주문 데이터를 가져오는 유일한 통로다.
- 어댑터는 읽기 전용 뷰 엔티티(`OrderProductSource`, Hibernate `@Subselect` / `@Immutable`)를
  통해 `order_product`를 직접 읽는다.
- "정산 대상" 필터는 쿼리에서 건다: `order_product_status = 'PAID'`, 기간 내,
  그리고 `order_product_id not in (select from SettlementDetail)`로 이미 정산된 라인은 제외.

```
settlement-service ──SELECT──▶ order_product (order 소유)
```

이 방식은 DB를 공유하거나, 최소한 order 테이블에 읽기 접근이 있다는 걸 전제한다. 정산이
order 스키마에 결합되는 구조다. 모놀리식/공유 DB에선 괜찮지만, 서비스를 쪼갤 때 끊어내야 할
결합이 바로 이것이다.

## 결정해야 할 것

order가 서비스 경계 뒤에서 자기 데이터를 소유하게 되면, 정산은 필요할 때 당겨오거나(pull)
미리 받아두거나(push) 둘 중 하나다. 선택 기준은 **데이터의 성격**이지, 누가 실행을 트리거했는지가
아니다.

- **gRPC (pull):** 배치 시점에 order에게 해당 기간 PAID 라인을 요청한다. order가 진실의 원천으로
  남고, 정산은 "아직 정산 안 됨" 필터를 자기 `SettlementDetail` 기준으로 직접 건다. 동기 호출 한 번
  (양이 많으면 페이징/스트리밍).
- **Kafka (push):** order가 결제 발생 시점마다 이벤트를 발행하고, 정산은 로컬에 사본을 쌓아두며
  배치는 그 사본을 읽는다. 배치가 도는 시점에 order가 떠 있지 않아도 된다.

수동 실행이든 스케줄 실행이든 정산이 필요로 하는 데이터는 같다: "이 기간의 정산 대상 라인."
그러니 전송 방식도 둘 다 같아야 한다. 트리거 기준으로 쪼개면(수동 → 이벤트, 스케줄 → gRPC)
같은 데이터를 가져오는 경로가 둘이 된다 — 코드도 두 배고, 한쪽이 뒤처지면 같은 질문에 답이 둘이
되어 어긋날 수 있다.

## 선택지

| 방식 | 장점 | 단점 |
| --- | --- | --- |
| 테이블 직접 조회 (현재) | 가장 단순; 항상 최신 데이터; 추가 인프라 없음 | order 스키마에 결합; 공유 DB 필요; 서비스 분리 후엔 불가 |
| gRPC pull (단일 전송) | order가 진실의 원천; 로컬 복제본 없음; 시점 일관성 깔끔; dedup은 로컬 유지 | 배치 시점에 order가 떠 있어야 함; 대량 조회는 페이징/스트리밍 필요 |
| Kafka push (단일 전송) | order 가용성과 무관; 독립 배포 | 로컬 사본 + 중복/순서/재처리 관리; 최종 일관성; 스트림 위에서 기간 경계를 직접 그어야 함 |
| 트리거 분리 (수동=이벤트, 스케줄=gRPC) | — | 같은 데이터에 경로 둘; 정합성 리스크; 수동과 스케줄이 정말 다른 걸 정산할 때만 정당화됨 |

## 방향

- 두 트리거에 **하나의** 전송 방식을 쓴다. 수동과 스케줄은 트리거만 다를 뿐 읽는 데이터는 같다.
- 기간 스냅샷을 읽는 주기 배치이고, order가 진실의 원천이며, dedup 필터가 정산 쪽에 있다는 점을
  보면 **gRPC pull이 더 단순한 선택**이다. 단, order의 가용성과 무관하게 정산이 돌아야 한다는
  요구가 분명하면 Kafka로 간다.
- 트리거 분리 하이브리드는 "수동"이 방금 발생한 특정 건을 정산하는 것(이벤트 페이로드 자체가
  데이터)이고 "스케줄"이 기간 전체를 쓸어담는 경우에만 값을 한다. 둘 다 같은 기간을 정산한다면
  쪼개지 마라. 쪼갠다면 두 경로 모두 같은 `SettlementDetail` 기준으로 dedup 해서 이중 정산을 막아야
  한다.
- 어느 쪽이든 바뀌는 건 어댑터뿐이다. `SettlementSourceRepository`(포트)와 계산 유스케이스는
  그대로라, 안쪽 계층을 건드리지 않고 교체할 수 있다.

## 이벤트 설계 (선택한 방향)

Kafka로 간다. 골자는, order가 paid 라인 이벤트를 발행하고 정산은 정산 대상 라인의 사본을 자기
쪽에 보관하며 배치가 그 사본을 읽는 것이다. `order_product` 위의 `@Subselect`는 없어진다.

### 형태: 데이터를 이벤트에 실어 보낸다

이벤트가 정산에 필요한 필드를 담아서, 정산이 order로 되묻지 않게 한다(event-carried state
transfer). 비동기로 가는 이유가 이것이다 — 데이터를 채우려고 여전히 order를 호출해야 한다면
gRPC가 더 간단하다.

정산이 필요로 하는 건 작다: order product id, seller id, 금액, 결제 시각.

```
order_product PAID  ──event──▶  Kafka topic  ──▶  settlement consumer
                                                      └─ settleable_order_line(로컬)에 upsert
                                                            └─ 배치가 로컬 테이블을 읽음
                                                                  └─ SettlementDetail로 dedup
```

### 토픽과 계약(contract)

- 토픽: order-product 라이프사이클 사실 단위로 하나, 예: `order-product-paid`
  (취소/환불은 `order-product-cancelled` / `-refunded` 추가 — 아래 참고).
- 키: `orderProductId`. 같은 라인의 paid→cancel 이벤트가 같은 파티션에 떨어져 순서가 유지된다.
- 페이로드(팀 간 안정 계약 — 버전을 매기고, 변경은 호환 깨짐으로 취급):

```json
{
  "orderProductId": "uuid",
  "sellerId": "uuid",
  "amount": 12000,
  "occurredAt": "2026-06-03T10:15:30",
  "eventId": "uuid"
}
```

### 컨슈머 측 (정산)

- 새 로컬 테이블 `settleable_order_line`이 `@Subselect` 뷰가 읽던 것을 대신 보관한다. 정산이
  소유하고, 컨슈머만 채운다.
- `infrastructure/event`의 `@KafkaListener`가 이벤트를 이 테이블에 upsert 한다.
- `SettlementSourceRepositoryAdapter`는 이제 `order_product`가 아니라 이 로컬 테이블을 읽는다.
  기간 필터와 `not in SettlementDetail` dedup은 그대로 — 대상만 로컬 테이블로 바뀐다.
- paid 라인은 들어오는 대로 전부 저장한다. "정산됨/안됨"을 컨슈머에서 추적하지 마라. 그건 배치가
  이미 `SettlementDetail` 기준으로 거른다. 컨슈머는 단순하게, dedup은 한곳에.

### 멱등성 (필수)

Kafka는 at-least-once라 같은 이벤트가 두 번 올 수 있다. upsert는 멱등이어야 한다.

- 로컬 행을 `orderProductId`(unique)로 식별한다. 재전송은 같은 행을 덮어쓰니 중복이 없다.
- blind insert가 아니라 insert-or-update(`ON CONFLICT (order_product_id) DO UPDATE` / merge)를 쓴다.
- 행이 영속화된 뒤에만 ack 한다. 처리 도중 크래시는 유실이 아니라 재처리가 되도록.

### 역전 처리 (paid → 취소 / 환불)

결제됐던 라인이 이후 정산 대상에서 빠질 수 있다. 어떻게 모델링할지 order 팀과 정한다.

- order가 같은 `orderProductId`에 대해 취소/환불 이벤트를 발행한다.
- 아직 정산 전이면 → 로컬 라인을 제거(또는 플래그)해 집계에서 빠지게 한다.
- 이미 `SettlementDetail`에 들어갔으면 → 조용히 지우지 마라. 그건 다음 기간의 보정(환불 라인)이지
  삭제가 아니다. 기본값이 아니라 명시적 규칙이 필요하다.

### 신뢰성

- **발행 측 (order):** transactional outbox 패턴을 써서, DB 커밋은 성공했는데 broker 발행이 실패해도
  이벤트가 유실되지 않게 한다. 이건 order 쪽 전제 조건이다 — 없으면 정산이 조용히 라인을 놓쳐
  과소 정산할 수 있다.
- **소비 측 (정산):** 계속 실패하는 메시지를 위한 dead-letter topic을 두고, 거기로 보내기 전 제한된
  재시도를 둔다. poison 메시지가 파티션을 막으면 안 된다.

### 일관성 경계

로컬 사본은 최종 일관성을 가진다. 배치는 실행 시점에 로컬 테이블에 있는 것을 정산하고, 이벤트가
아직 도착하지 않은 라인은 다음 기간에 정산된다. 월 배치라면 괜찮다 — dedup이 이중 정산을
보장하니 지연은 단지 미룸일 뿐이다. 늦은 이벤트를 버그로 오해하지 않도록 이 점을 명시해 둔다.

### 코드에서 바뀌는 것

- 신규: `settleable_order_line` 엔티티 + 테이블; `infrastructure/event`의 Kafka 리스너; 멱등 upsert
  리포지토리.
- 변경: `SettlementSourceRepositoryAdapter`가 로컬 테이블을 읽음; `OrderProductSource`(`@Subselect`)
  제거.
- 그대로: `SettlementSourceRepository` 포트, `CalculateSettlementUseCase`, 배치 자체.

### 남은 질문

- 위 역전 규칙(정산 후 취소/환불) — order와 합의된 정책이 필요하다.
- order 쪽 outbox — 스트림에 의존하기 전에 적용돼 있는지 확인한다.
- 백필 — 컨슈머가 생기기 전 결제된 주문에 대해 로컬 테이블을 어떻게 채울지(일회성 리플레이,
  또는 컷오버 시 벌크 임포트).
