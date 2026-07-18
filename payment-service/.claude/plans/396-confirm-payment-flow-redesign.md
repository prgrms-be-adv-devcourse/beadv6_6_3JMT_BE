# 결제 승인 흐름 재정렬 구현 계획

결제 승인(`ConfirmPaymentService`) 흐름을 목표 시퀀스 다이어그램 기준으로 재정렬한다 — 금액 검증을 명시적 단계로 도입하고, 중복 요청 판정 방식을 사전체크로 바꾸고, 주문 정보 로컬 캐시(`OrderSnapshot`)를 제거해 매 요청 gRPC 직접 조회로 전환하고, 결제 실패 후 재결제를 막고, Kafka 이벤트 payload를 필요한 필드만 남기도록 정리한다.

---

## 배경 및 목표

결제 승인 흐름을 담은 시퀀스 다이어그램(Client → Toss → Payment → Order → Kafka)과 실제 구현(`ConfirmPaymentService`)을 대조한 결과 다음 지점에서 어긋나 있었다.

- 금액 검증 로직 자체가 실제 코드에 없었다 — client가 보낸 금액을 아예 받지 않고, 주문 스냅샷 값을 무조건 신뢰하는 구조였다.
- 중복 요청 판정이 다이어그램은 paymentKey 사전 존재 확인인데 반해, 실제는 orderId+상태 사전 체크와 DB UNIQUE 제약 위반 catch를 섞어 쓰고 있었다.
- 다이어그램은 매 요청 gRPC로 주문 정보를 조회하는데, 실제는 로컬 `OrderSnapshot` 캐시를 우선 조회하고 캐시 미스일 때만 gRPC로 폴백하는 구조였다.

이 계획은 다이어그램이 그리는 흐름으로 실제 구현을 맞추는 것을 목표로 한다. 단, 다이어그램에 안 그려졌다는 이유로 기존에 이미 확정된 프로젝트 규칙(예: X-User-Id 기반 본인 확인)을 후퇴시키지는 않는다.

브레인스토밍 과정에서 두 가지가 스코프에 추가됐다. 첫째, 결제 실패 후 같은 주문으로 재결제를 허용하던 기존 정책을 폐기하고, 실패한 주문은 새 주문으로만 다시 결제할 수 있게 한다. 둘째, 결제 성공/실패 Kafka 이벤트 payload를 실제로 필요한 필드만 남기도록 정리한다(뒤 섹션 참조).

## 왜 이 설계로 바꿨는가 — 기존 설계의 문제와 개선 이점

각 항목의 상세 근거는 "확정 사항" 절에 있다. 여기서는 기존 설계가 구체적으로 무엇이 문제였고, 변경으로 실제 무엇을 얻는지를 항목별로 정리한다.

**1. 금액 검증 부재 → 조기 차단 방어 계층 추가**

기존 설계는 "금액의 진실 공급원은 서버"라는 원칙을 client 금액을 아예 안 받는 방식으로 구현했다. 원칙 자체는 맞지만, 그 결과 프론트가 화면에 표시한 금액과 서버가 실제로 청구하는 금액이 어긋나는 상황(가격 변경, 캐시된 화면, 프론트 버그 등)을 감지할 방법이 코드에 없었다 — 서버는 그냥 자기 값으로 조용히 진행해버린다. 방어 계층을 하나 추가해 client 금액과 서버 진실값을 비교하고, 어긋나면 Toss API를 호출하기 전에 즉시 400으로 차단한다. 얻는 것: PG사 호출 전 조기 실패(불필요한 PG 트래픽·과금 방지), 사용자에게 PG 응답 대기 없이 즉각적인 실패 피드백, 그리고 프론트-서버 금액 불일치라는 실제 장애 시나리오에 대한 가시성(로그·이벤트로 남음).

**2. 사후 예외 처리 기반 중복판정 → 사전 조회 기반으로 전환**

기존 코드는 "일단 INSERT를 시도하고, DB UNIQUE 제약이 걸리면 그 예외를 잡아 409로 변환"하는 방식이었다. 이 방식은 정상적인 중복 요청(가장 흔한 케이스)까지 매번 쓰기 시도 → 제약 위반 → 예외 캐치라는 무거운 경로를 타야 했고, 코드 흐름도 예외 기반이라 다이어그램이 보여주는 "먼저 확인하고 아니면 진행"이라는 직관적 순서와 어긋났다. `existsByPgTxId`/`existsByOrderIdAndStatusIn` 사전 조회로 바꾸면서 정상 경로에서는 예외가 아예 발생하지 않고, DB 제약 위반은 사전 체크 사이의 극히 좁은 레이스 케이스에만 남는 최후 방어선으로 축소됐다. 얻는 것: 흔한 경로의 성능(불필요한 쓰기 시도 제거), 코드 가독성(흐름이 다이어그램과 1:1 대응), 그리고 "왜 이 요청이 막혔는가"를 예외 스택 없이 바로 알 수 있는 명시성.

**3. `OrderSnapshot` 이중 캐시 구조 → 단일 진실 공급원(gRPC 직접 조회)**

기존 구조는 주문 정보를 로컬 테이블에 캐시해두고, `order-events`의 `ORDER_CREATED` 이벤트(평상시 경로)와 gRPC 폴백(캐시 미스 시 경로) 두 갈래로 채웠다. 문제는 이 이원화 자체가 비용이었다는 점이다 — 두 경로가 동시에 같은 스냅샷을 쓰려는 경쟁 조건을 처리하는 코드(`REQUIRES_NEW` 트랜잭션 분리, 유니크 충돌 시 재조회)가 별도로 필요했고, 캐시 테이블·엔티티·리포지토리·유스케이스가 통째로 하나의 관심사(정합성 유지)만을 위해 존재했다. 게다가 이 이벤트 경로는 order-service가 아직 `ORDER_CREATED`를 발행하지 않아(모노레포 문서상 "예정" 상태) 실제로는 늘 gRPC 폴백만 타는 반쪽짜리 구현이었다. 캐시를 통째로 걷어내고 매 요청 gRPC 직접 조회로 단순화하면서, 이 관심사 전체(정합성 관리 코드, 이벤트 소비 분기, 테이블)가 사라졌다. 얻는 것: 삭제된 파일 수만큼의 유지보수 부담 감소, 캐시 무효화·정합성 버그라는 문제 클래스 자체의 소멸, order-service의 미구현 이벤트 발행에 더 이상 의존하지 않는 독립성. 트레이드오프는 매 요청 네트워크 호출이 늘어난다는 것인데, 이를 트랜잭션 밖으로 빼서(Toss 호출과 동일한 패턴) DB 커넥션 점유 문제로 번지지 않도록 상쇄했다.

**4. 재결제 무제한 허용 → 실패 시 영구 차단**

기존 정책은 결제가 실패해도 같은 주문으로 몇 번이든 다시 시도할 수 있게 허용했다(시도마다 새 Payment 행 생성). 이번 요구사항 변경으로 한 번 실패한 주문은 재결제를 막고 새 주문으로만 다시 결제하도록 정책을 바꿨다. 얻는 것: 한 주문에 대해 결제 시도 이력이 최대 하나로 좁혀져 상태 추적이 단순해지고("이 주문의 결제 상태가 뭐야"에 대한 답이 항상 명확), order-service 쪽에서도 `FAILED → PAID` 복귀라는 예외적인 상태 전이 분기를 정리할 수 있는 계기가 된다.

**5. Kafka payload 과다 노출 → 필요한 필드만 발행**

기존 `PAYMENT_APPROVED`/`PAYMENT_FAILED` payload는 `userId`/`paymentId`를 포함하고 있었는데, 정작 order-service의 실제 소비 로직은 이 필드들을 쓰지 않았다(`orderId`만 있으면 충분). 게다가 order-service가 최근 리팩터링한 소비 측 타입은 오히려 payment-service가 안 보내는 필드(`pgTxId` 등)를 기대하고 있어, 두 서비스의 계약이 이미 어긋나 있다는 사실도 이번에 드러났다. payload를 실제 필요분으로 정리하면서 얻는 것: 메시지 크기·불필요한 데이터 노출 감소, 그리고 어긋나 있던 크로스서비스 계약을 바로잡을 계기(다만 order-service 쪽 수정은 담당자 조율 필요 — 아래 "리포 스코프 밖" 참조).

## 확정 사항

**client 요청에 amount 필드를 추가하고, 주문 gRPC 응답의 totalAmount와 비교하는 명시적 검증 단계를 도입한다.** 기존 설계는 "금액의 진실 공급원은 서버(스냅샷/주문 정보)"라는 원칙 아래 client 금액을 아예 받지 않았다. 이 원칙 자체는 유지하되(Payment 엔티티에 실제로 저장되는 금액은 여전히 주문 쪽 값), client가 보낸 금액과 서버 진실값을 비교해 어긋나면 Toss 호출 전에 즉시 차단하는 방어 계층을 추가한다. 프론트에 표시된 금액과 서버 금액이 어긋나는 상황(가격 변경, 캐시된 화면 등)을 PG사까지 안 가고 조기에 걸러낼 수 있다.

**중복 요청 판정을 `existsByPgTxId`(paymentKey) + `existsByOrderIdAndStatusIn`(orderId·상태) 두 가지 사전 조회로 교체하고, DB UNIQUE 제약 위반을 catch해 409로 매핑하던 기존 로직은 제거한다.** paymentKey 사전 체크만으로 교체하면 "이미 결제 완료된 주문에 다른 paymentKey로 재시도"하는 경우를 못 막는다는 문제가 있어(같은 주문·다른 키), orderId+상태 체크를 별도로 남겨 두 케이스를 각각 방어한다. DB catch 로직 제거로 인해 두 사전 체크 사이의 좁은 레이스 윈도우에서 UNIQUE 제약 위반이 그대로 500으로 노출될 수 있다는 트레이드오프는 "알려진 한계"에 기록한다.

**`OrderSnapshot` 로컬 캐시(테이블·엔티티·리포지토리·유스케이스 전체)를 삭제하고, 매 확인 요청마다 order-service에 gRPC로 직접 조회한다.** 다이어그램이 요구하는 흐름이 이것이고, 캐시가 사라지면 스냅샷 정합성 관리(이벤트 vs 조회 두 경로의 경쟁 처리 등) 자체가 불필요해져 코드 복잡도가 준다.

**주문 정보 gRPC 조회를 트랜잭션 밖으로 뺀다.** 캐시가 있을 때는 캐시 미스 케이스에서만 예외적으로 트랜잭션 안에서 gRPC를 호출했지만, 캐시를 없애면 이 호출이 매 요청 트랜잭션 안에서 발생해 DB 커넥션을 네트워크 왕복 시간만큼 붙잡게 된다. 기존 코드가 Toss 승인 호출을 이미 트랜잭션 밖으로 빼둔 것과 같은 이유로, order-service gRPC 호출도 트랜잭션 진입 전에 실행한다.

**본인 확인(ownership) 체크는 그대로 유지한다.** `.claude/rules/api-error-handling.md`가 "역할 기반 인가 대신 X-User-Id로 본인 확인을 직접 검증한다"는 규칙을 이미 못박아 뒀고, 결제 확인이 그 규칙이 드는 예시 사례 자체다. 다이어그램에 이 체크가 안 그려진 건 시퀀스 흐름을 단순화해 보여주려는 것일 뿐, 이 규칙을 철회하려는 의도로 보기 어렵다. 비용도 사실상 0이다 — order-service gRPC 응답(`GetOrderResponse`)에 `buyerId`가 이미 포함돼 있어 별도 조회나 프로토콜 변경 없이 그 자리에서 비교만 하면 된다. 다만 로컬에는 저장하지 않는다(캐시 제거 원칙과 일관).

**`Payment.fail()`의 상태 가드를 완화해 READY·REQUESTED 양쪽에서 FAILED로 전이할 수 있게 한다.** 금액 불일치는 REQUESTED로 전이하기 전(READY 상태)에 실패 처리해야 하는데, 기존 `fail()`은 REQUESTED에서만 호출 가능하도록 막혀 있어 이 케이스를 표현할 수 없었다.

**`PaymentStatus` enum 이름은 바꾸지 않는다.** 다이어그램의 `CREATED`는 실제로는 `READY`가 그 역할을 한다. 이름만 다를 뿐 의미가 같은데, enum 값을 바꾸면 Flyway 마이그레이션까지 필요해지는 비용 대비 얻는 게 없다고 판단했다.

**신규 에러 코드 `AMOUNT_MISMATCH`(400)를 추가한다.** 금액 불일치를 표현할 기존 코드가 없었다(`INVALID_INPUT`은 일반 입력값 오류라 의미가 다름).

**`BLOCKING_STATUSES`에 `FAILED`를 추가해 결제 실패 후 같은 주문으로의 재결제를 막는다.** 기존엔 "REQUESTED·FAILED·READY는 비차단(재결제 허용)"이 원칙이었으나, 실패한 주문은 새 주문(새 orderId)으로만 다시 결제하도록 정책이 바뀌었다. 변경 후 `BLOCKING_STATUSES = {PAID, FAILED, PARTIAL_REFUNDED, ALL_REFUNDED, UNKNOWN}` — READY·REQUESTED(진행 중인 시도 자체)만 비차단으로 남는다. order-service 쪽도 `FAILED → PAID` 복귀를 더 이상 허용하지 않도록 맞춰야 하는데, 이는 order-service 담당자에게 별도로 전달한다(Kafka payload 변경 건과 함께, 아래 참조).

**Kafka `PAYMENT_APPROVED`/`PAYMENT_FAILED` 이벤트 payload를 필요한 필드만 남긴다.** order-service의 실제 소비 로직(`PaymentApprovedProcessor`/`PaymentFailedProcessor`)을 확인한 결과 두 이벤트 모두 현재 payload보다 훨씬 적은 필드만 실제로 쓰이고 있었고, 반대로 order-service가 최근(2026-07-08) 리팩터링한 소비 측 payload 타입은 오히려 payment-service가 지금 안 보내는 필드(`pgTxId`, `paymentMethod`, `provider` 등)를 기대하는 등 양쪽 계약이 이미 어긋나 있었다. 이번 기회에 payment-service가 보내는 필드를 payment-service 관점에서 실제로 필요한 최소 집합으로 정리하고, order-service 쪽 소비 코드는 담당자에게 요청해 맞추기로 했다(이 리포 스코프 밖).

- `PaymentApprovedMessage`: `orderId`, `approvedAmount`, `approvedAt` — `userId`·`paymentId` 제거
- `PaymentFailedMessage`: `orderId` — `userId`·`paymentId` 제거

`userId`(구매자)는 order-service가 자기 `Order` 엔티티로 이미 갖고 있어 payment-service가 다시 보낼 필요가 없다. `paymentId`는 애초엔 "같은 주문에 여러 결제 시도가 있을 때 이 이벤트가 어느 시도에 대한 건지 식별하는 용도"로 남기는 걸 검토했으나, 위 재결제 차단 정책으로 한 주문당 결제 시도가 사실상 하나로 좁혀지면서(동시성 레이스 제외) 그 근거가 약해져 함께 제거하기로 했다.

## 새 확인 흐름

```
1. (TX 밖) existsByPgTxId(paymentKey) → 존재 시 409 DUPLICATE_PAYMENT
2. (TX 밖) existsByOrderIdAndStatusIn(orderId, BLOCKING_STATUSES={PAID,FAILED,PARTIAL_REFUNDED,ALL_REFUNDED,UNKNOWN}) → 존재 시 409 DUPLICATE_PAYMENT
3. (TX 밖) orderGateway.getOrderPaymentInfo(orderId) — gRPC, 매 요청마다 호출
4. ownership 체크 (orderInfo.buyerId == command.userId) → 불일치 시 403 NOT_ORDER_OWNER
5. amount 검증 (command.amount == orderInfo.totalAmount)
   - 불일치: 짧은 TX에서 Payment 생성(READY) 후 즉시 fail() 전이 → PaymentFailedEvent 발행 → 커밋 → 400 AMOUNT_MISMATCH 응답
   - 일치: 짧은 TX에서 Payment 생성(READY) + markRequested(REQUESTED) → 커밋
6. (TX 밖) Toss 승인 호출 — 기존과 동일
7. 성공: TX에서 approve() + PaymentApprovedEvent 발행 — 기존과 동일
8. 실패(PG): TX에서 fail() + PaymentFailedEvent 발행 — 기존과 동일 (이 주문은 이후 영구 차단, 재결제 불가)
```

## Request/Command 변경

- `ConfirmPaymentRequest`: `amount`(Integer, `@NotNull`) 필드 추가
- `ConfirmPaymentCommand`: `amount`(int) 필드 추가

## 삭제 대상

- `domain/model/OrderSnapshot.java`, `domain/model/OrderSnapshotSource.java`
- `domain/repository/OrderSnapshotRepository.java`
- `application/dto/command/RecordOrderSnapshotCommand.java`
- `application/usecase/RecordOrderSnapshotUseCase.java`, `application/service/RecordOrderSnapshotService.java`
- `infrastructure/persistence/OrderSnapshotRepositoryAdapter.java`, `OrderSnapshotJpaRepository.java`
- `infrastructure/messaging/consumer/OrderEventConsumer`에서 `ORDER_CREATED` 분기(핸들러/상수/검증/`OrderCreatedMessage` 의존)만 제거 — `ORDER_REFUND_REQUESTED` 분기는 그대로 유지
- `db/migration/V3__drop_order_snapshot.sql` 신설 — `order_snapshot` 테이블 drop (이미 배포된 V1·V2는 수정하지 않는다)

## 알려진 한계

paymentKey/orderId 사전 체크와 실제 INSERT 사이에 좁은 레이스 윈도우가 있다. DB `pg_tx_id` UNIQUE 제약은 스키마 레벨로 남아있어 데이터 정합성 자체는 깨지지 않지만, 애플리케이션 코드에서 그 위반을 잡아 409로 매핑하던 로직을 제거했기 때문에 이 좁은 레이스에서 제약 위반이 나면 500으로 노출된다. 현재 트래픽 규모에서는 감수 가능하다고 보고 진행하며, 문제가 되면 해당 구간에 한정된 catch를 다시 추가하면 된다.

## 테스트 영향 범위

- `ConfirmPaymentServiceTest`, `ConfirmPaymentIntegrationTest`: 스냅샷 관련 목킹 제거, gRPC 매 요청 호출 목킹으로 전환, amount 필드·불일치 케이스 추가, FAILED 상태 주문 재결제 시 409 케이스 추가
- `OrderSnapshotJpaRepositoryTest`: 삭제
- `OrderEventConsumerIntegrationTest`: `ORDER_CREATED` 케이스 제거, `ORDER_REFUND_REQUESTED` 케이스는 유지
- Kafka 발행 관련 테스트: `PaymentApprovedMessage`/`PaymentFailedMessage` 필드 변경(축소) 반영

## 문서 후속 반영 필요

구현 PR에서 아래 문서를 함께 갱신한다.

- `.claude/docs/api-design.md` — `ConfirmPaymentRequest`에 `amount` 필드 반영
- `.claude/docs/db-schema.md` — `order_snapshot` 테이블 제거 반영
- `../docs/architecture/event-flow.md` — payment-service가 더 이상 `ORDER_CREATED`를 소비하지 않음을 반영
- `.claude/docs/events.md` — `PAYMENT_APPROVED`/`PAYMENT_FAILED` payload 스키마 축소 반영, "재결제 시 FAILED → PAID 복귀 허용" 문구 제거(더 이상 허용 안 함)

## 리포 스코프 밖 — order-service 담당자 조율 필요

- `PaymentApprovedPayload`/`PaymentFailedPayload`(order-service 소비 측)를 이번에 정리한 payment-service 발행 필드에 맞춰 수정 요청. 특히 `OrderPolicyService.validatePaymentApproval()`이 `payload.approvedAmount()`로 자체 금액 재검증을 하고 있어 필드명 정합이 특히 중요하다.
- `PaymentApprovedProcessor`가 갖고 있는 `FAILED → PAID` 복귀 허용 분기(주문 상태가 `FAILED`여도 승인 이벤트를 받아들이는 로직)는 재결제 차단 정책상 더 이상 발생하지 않는 경로가 되므로 정리 여부를 담당자와 상의.
