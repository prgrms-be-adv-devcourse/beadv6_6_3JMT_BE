# 환불 흐름 개선 (refundRequestId 기반 재환불 허용) 구현 계획

환불 dedup 키를 `(payment_id, order_product_id)`에서 order-service가 발급하는 `refundRequestId`로 전환해 동일 상품에 대한 재환불(여러 차례 부분환불)을 허용하고, payment-events 계약을 단순화한다.

---

## 배경 및 목표

현재 payment-service의 환불 처리(`ProcessRefundService`)는 이미 Kafka 이벤트 기반으로 동작하며, `refund` 테이블의 `(payment_id, order_product_id)` UNIQUE 제약으로 중복 이벤트 처리를 막고 있다. 이 방식에는 두 가지 문제가 있다.

1. **재환불 불가**: 동일 `order_product`에 대해 평생 단 1회만 환불이 가능하다. 상품 하자가 여러 차례에 걸쳐 발견되는 등 같은 상품에 대한 복수 부분환불 요구를 처리할 수 없다.
2. **과환불 검증 실패가 예외로 처리됨**: 현재는 과환불 검증 실패 시 `Refund` row를 만들기도 전에 예외를 던진다. 과환불은 재시도해도 항상 같은 결과가 나오는 확정적 비즈니스 규칙 위반인데, Kafka `DefaultErrorHandler`가 이를 일시적 오류처럼 3회 재시도 후 DLT로 보낸다. 감사 기록(Refund row)도 남지 않는다.

이번 변경의 목표는 (1) dedup 키를 요청 단위(`refundRequestId`)로 바꿔 동일 상품 재환불을 허용하고, (2) 과환불 실패를 예외가 아닌 정상 실패 흐름(이벤트 발행)으로 통일하는 것이다.

## 확정 사항

**1. dedup 키를 `refundRequestId`로 전환한다.**
order-service가 `ORDER_REFUND_REQUESTED` 이벤트 발행 시 `refundRequestId`(UUID)를 새로 생성해 payload에 실어 보내기로 합의됨(이 계약 변경은 order-service 팀과 이미 확정). 기존 `(payment_id, order_product_id)` UNIQUE 제약은 "상품당 평생 1회"라는 의도치 않은 제약을 만들었으므로, 요청 단위로 유일성을 관리하는 `refundRequestId`로 대체한다.

**2. `Refund` 엔티티 생성 시점을 dedup 통과 직후, 과환불 검증 이전으로 앞당긴다.**
현재 코드가 과환불 검증 통과 후에만 `Refund.create()`를 호출하는 이유는 기존 dedup 키(`payment_id, order_product_id`) 구조상 검증 실패로 생성된 row가 해당 상품의 향후 정상 환불까지 영구 차단하기 때문이었다. dedup 키를 `refundRequestId`로 바꾸면 이 제약이 사라지므로, 생성 시점을 앞당겨도 다른 요청을 차단하지 않는다. 앞당기면 과환불 거부도 `Refund` row(FAILED)로 남아 감사 추적이 가능해지고, 성공/실패 두 경로 모두 "생성 → 검증 → 상태갱신 → 이벤트 발행"이라는 동일한 형태를 갖게 되어 코드가 대칭적이 된다.

**3. `RefundStatus`는 기존 3단계(`REQUESTED`/`COMPLETED`/`FAILED`)를 그대로 유지한다.**
초기 검토 시 `CREATED`/`REFUND_REQUESTED`/`SUCCESS`/`FAILED` 4단계 확장을 고려했으나, PG 호출 전후 상태 전이를 모두 한 트랜잭션 안에서 처리하기로 결정했기 때문에(아래 8번 참조) JPA는 트랜잭션 커밋 시점에 최종 상태만 flush한다. 즉 `REFUND_REQUESTED` 같은 중간 상태는 다른 트랜잭션에서 절대 관측되지 않는 죽은 상태값이 된다. 관측 불가능한 상태를 위해 상태 전이 검증 로직 전체를 한 단계 더 복잡하게 만들 이유가 없어 기존 3단계를 유지한다. 향후 PG 호출을 별도 트랜잭션으로 분리하고 재시도 스케줄러를 도입하는 시점이 오면, 그때 실제로 관측 가능한 중간 상태로서 확장하면 된다.

**4. `refund` 테이블의 `order_product_id` 컬럼을 완전히 삭제한다.**
재환불을 허용하면 같은 `order_product_id`에 여러 `Refund` row가 생길 수 있어 이 컬럼의 조회 가치가 떨어진다. `refundRequestId`가 이미 요청 단위 식별자 역할을 하므로, "어떤 상품이 환불되었는지"에 대한 추적 책임은 그 값을 발급한 order-service 쪽에 둔다. 트레이드오프: payment-service 자체 DB만으로는 상품별 환불 내역을 조회할 수 없게 된다(아래 리스크 참조).

**4-1. `refund` 테이블의 `user_id` 컬럼도 함께 삭제한다.**
코드 조사 결과 `Refund.userId`는 생성 시 저장만 되고 어디서도 읽히지 않는 죽은 필드였다(실제 이벤트 발행/조회는 전부 `Payment.getUserId()`를 사용). 과거 REST 기반 환불 API 시절(현재는 이벤트 기반으로 전환됨) 요청자 본인확인용으로 남아있던 흔적으로 추정된다. 이번에 스키마를 만지는 김에 함께 정리한다. 인바운드 `ORDER_REFUND_REQUESTED`의 `buyerId` 필드는 이제 파싱/매핑하지 않는다(order-service가 여전히 보내더라도 무시).

**5. 아웃바운드 이벤트(`PAYMENT_REFUNDED`, `PAYMENT_REFUND_FAILED`) payload를 최소 필드로 축소한다.**
`orderId, refundAmount, refundedAt`(성공) / `orderId, refundAmount, failedAt`(실패)만 남기고 `paymentId`, `userId`, `paymentStatus`, `orderProductId`, `refundRequestId`, `failureReason`을 전부 제외한다. `orderId`만으로 order-service 자신의 로직으로 상관관계 추적이 충분하다는 판단에 따른 것. `failureReason`은 payment-service 내부 DB(`Refund.reason`)에는 그대로 남기고, 외부 발행 payload에서만 제외한다. **리스크**: `paymentId`/`userId`/`paymentStatus` 등을 settlement-service 등 다른 기존 구독자가 이미 사용하고 있는지는 이번 설계에서 확인하지 않았다 — 구현 전 별도 확인 필요(아래 리스크 섹션 참조).

**6. `GetRefundUseCase`/`GetRefundService`와 이를 노출하는 gRPC 폴백 조회를 완전히 제거한다.**
기존 목적은 order-service가 Kafka 이벤트를 못 받았을 때 동기 폴백으로 결과를 확인하는 것이었다. order-service가 이벤트 유실 시 Kafka 자체에서 재조회/재처리하는 방식으로 커버 가능하다는 판단에 따라, 이 동기 폴백 경로는 더 이상 필요하지 않다고 결정함. **리스크**: 이 폴백을 없애면 Kafka 메시지가 실제로 유실되는 극단적 상황에서 order-service가 결과를 확인할 다른 수단이 없어진다 — order-service 쪽 재조회 능력이 실제로 이 gap을 메우는지는 이번 설계 범위 밖.

**7. Payment row에 대한 `SELECT ... FOR UPDATE` 락 조회는 그대로 유지한다.**
동일 payment에 대한 환불 요청이 (드물지만) 동시에 처리될 경우, 락 없이는 둘 다 "현재까지 누적 환불액"을 동시에 읽고 각자 통과 판정을 내려 합산 시 과환불이 발생하는 lost-update 문제가 생긴다. Kafka 파티션 키가 `orderId`라면 정상적으로는 순차 처리되지만, 리밸런싱/재시도/다중 인스턴스 상황까지 대비하는 안전장치로 유지한다.

**8. PG(Toss) 호출과 상태 전이를 하나의 트랜잭션 안에서 처리하는 기존 방식을 유지한다.**
CREATED/REFUND_REQUESTED 단계를 별도 커밋으로 쪼개 크래시 복구용 중간 상태를 만드는 대안도 검토했으나, 이는 별도 재시도/정리 스케줄러 도입을 전제로 하는 설계 확장이라 이번 변경 범위에서 제외한다.

## 데이터 모델 변경

`Refund` 엔티티 필드: `id, paymentId, refundRequestId(신규, UUID, UNIQUE), refundAmount, reason, status, requestedAt, completedAt, createdAt, updatedAt`. `orderProductId`, `userId`(죽은 필드) 필드 삭제.

과환불 누적액 계산 시 `status = COMPLETED`인 `Refund` row만 합산한다(진행 중이거나 실패한 시도는 제외).

### 마이그레이션

현재 최신 버전은 `V3__drop_order_snapshot.sql`. 다음 마이그레이션은 `V4__add_refund_request_id_and_relax_unique_constraint.sql`.

- `refund` 테이블에 `refund_request_id UUID NOT NULL` 컬럼 추가
- `refund_request_id`에 UNIQUE 제약 추가
- 기존 `(payment_id, order_product_id)` UNIQUE 제약 제거
- `order_product_id` 컬럼 제거
- `user_id` 컬럼 제거(죽은 필드)

## 이벤트 계약 변경

**인바운드 `ORDER_REFUND_REQUESTED`** (order-service 발행, 토픽 `order-events`): `orderId, refundRequestId(신규), refundAmount, requestedAt`만 파싱한다. `orderProductId`, `buyerId`가 여전히 함께 오더라도 payment-service는 파싱/저장하지 않는다(둘 다 payment-service 내부에서 실사용처가 없는 죽은 데이터였음).

**아웃바운드 `PAYMENT_REFUNDED`** (토픽 `payment-events`): `orderId, refundAmount, refundedAt`.

**아웃바운드 `PAYMENT_REFUND_FAILED`** (토픽 `payment-events`): `orderId, refundAmount, failedAt`.

## 처리 흐름

1. `OrderEventConsumer`가 `ORDER_REFUND_REQUESTED` 수신, `ProcessRefundUseCase` 호출.
2. `refundRequestId`로 기존 처리 여부 조회 — 이미 처리된 요청이면 정상 종료(ack, 로그만 남김, 별도 이벤트 발행 없음).
3. 신규 요청이면 `Refund.create()`(status=`REQUESTED`, `refundRequestId` 저장).
4. Payment row `SELECT ... FOR UPDATE` 락 조회 (status가 `PAID`/`PARTIAL_REFUNDED`인 것만 대상).
5. 과환불 검증: `총 결제금액 - COMPLETED 상태 Refund 누적액 >= refundAmount`.
   - 실패 시: `Refund.fail()` + `PAYMENT_REFUND_FAILED` 이벤트 발행(정상 흐름, 예외 아님).
   - 성공 시: Toss 결제 취소 API 호출(`Idempotency-Key = refund-{refund.id}`, 기존과 동일).
     - PG 성공: `Refund.complete()` + `Payment.applyRefund()` + `PAYMENT_REFUNDED` 이벤트 발행.
     - PG 실패: `Refund.fail()` + `PAYMENT_REFUND_FAILED` 이벤트 발행.
6. 전체 과정은 하나의 트랜잭션 안에서 처리되며, `AFTER_COMMIT`에 Kafka 발행.

이 흐름에서 Kafka 재시도(3회) + DLT는 파싱 오류/인프라 장애 등 진짜 예외 상황에만 적용되고, 과환불 같은 확정적 비즈니스 실패는 더 이상 예외를 타지 않는다.

## 제거되는 컴포넌트

- `application/usecase/GetRefundUseCase`, `application/service/GetRefundService`
- 이를 노출하던 gRPC 서버 구현(정확한 클래스 경로는 구현 착수 시 grep으로 확인)

## 리스크 / 후속 확인 필요 사항

- **아웃바운드 payload 축소 영향 미확인**: `paymentId`/`userId`/`paymentStatus`/`orderProductId`/`failureReason`을 제거함에 따라, settlement-service 등 `payment-events`의 기존 구독자가 이 필드들을 실제로 쓰고 있었는지 이번 설계에서 확인하지 않았다. 구현 전 `../docs/architecture/event-flow.md` 및 관련 서비스 팀 확인 필요.
- **gRPC 폴백 제거에 따른 이벤트 유실 대응**: order-service가 Kafka 유실 시 자체적으로 재조회/재처리할 수 있다는 전제 하에 제거를 결정했다. 이 전제가 실제로 충족되는지는 payment-service 쪽에서 검증 불가능한 영역.
- **상품별 환불 내역 조회 불가**: `order_product_id` 컬럼 삭제로 payment-service DB만으로는 "이 refund가 어떤 상품 건인지" 추적할 수 없다. 필요 시 order-service 쪽 데이터에 의존해야 한다.

## 테스트 케이스

- 정상 부분환불(신규 `refundRequestId`) 처리 성공.
- 동일 `order_product`에 대한 두 번째 정상 부분환불(다른 `refundRequestId`) 성공 — 재환불 허용 확인.
- 동일 `refundRequestId` 이벤트 재전송(redelivery) 시 1회만 처리되고 중복 처리 안 됨.
- 과환불 시도 시 예외 없이 `Refund.FAILED` row 생성 + `PAYMENT_REFUND_FAILED` 이벤트 발행 확인(DLT로 가지 않음).
- 동시(concurrent) 환불 요청 시 Payment 락으로 순차 처리되어 과환불이 발생하지 않음.
- Toss 취소 API 실패 시 `Refund.FAILED` + `PAYMENT_REFUND_FAILED` 이벤트 발행, `Idempotency-Key`가 `refund.id` 기준으로 유지됨.
- `PAYMENT_REFUNDED`/`PAYMENT_REFUND_FAILED` 이벤트 payload가 축소된 필드 구성과 정확히 일치.
- `GetRefundUseCase`/`GetRefundService` 및 관련 gRPC 엔드포인트가 제거되고 남은 참조가 없음.
