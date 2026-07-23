# 주문 생성 시 본인 상품 구매 방지 설계

- 작성일: 2026-07-23
- 관련 이슈: https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/issues/520
- 대상: `order-service`의 `POST /api/v2/orders`

## 1. 배경과 목표

현재 주문 생성 흐름은 Product Service에서 받은 상품 스냅샷의 `sellerId`를 주문 상품에 저장하지만, 구매자 `buyerId`와 비교하지 않는다. 이 때문에 판매자가 자신의 무료·유료 상품을 직접 주문해 주문, 판매량, 이용 권한, 결제·정산 데이터에 잘못된 기록을 만들 수 있다.

주문 생성 시 요청 상품 중 하나라도 구매자와 판매자가 같으면 주문 전체를 거부한다. 검증은 상품 스냅샷 조회 이후, 주문 저장·장바구니 변경·Outbox 또는 애플리케이션 이벤트 생성 이전에 수행한다.

## 2. 범위

### 포함

- 무료·유료 상품의 본인 구매 차단
- 단건·다건 주문의 본인 구매 차단
- 직접 주문과 장바구니 기반 주문의 동일한 차단
- 다건 중 한 상품만 본인 상품이어도 주문 전체 거부
- 전용 `403 Forbidden` 오류 계약 추가
- 주문 생성 Swagger 설명과 응답 계약 갱신
- 정책, 애플리케이션 흐름, HTTP 계약 테스트 추가

### 제외

- 이미 생성된 셀프 주문과 관련 판매량·권한·결제·정산 데이터 보정
- Product Service 또는 Frontend 변경
- 주문 생성 요청·응답 DTO와 Kafka·gRPC·DB 스키마 변경

## 3. 오류 계약

`ErrorCode`에 다음 항목을 추가한다.

```text
enum: SELF_PURCHASE_NOT_ALLOWED
HTTP status: 403 Forbidden
code: O015
message: 본인이 판매하는 상품은 구매할 수 없습니다.
```

기존 중복 소유 오류 `ORDER_PRODUCT_ALREADY_OWNED(O018)`와 구분한다. 셀프 구매는 과거 구매 여부와 무관한 금지 정책이므로 항상 `O015`를 사용한다.

## 4. 설계

### 4.1 정책 위치

`OrderPolicyService`에 다음 책임을 추가한다.

```java
validateSelfPurchase(UUID buyerId, List<ProductOrderSnapshot> snapshots)
```

모든 상품 스냅샷을 순회해 `snapshot.sellerId()`가 `buyerId`와 같은지 확인한다. 하나라도 같으면 `OrderException(ErrorCode.SELF_PURCHASE_NOT_ALLOWED)`을 던진다.

이 정책은 Controller나 Infra가 아닌 Application 정책 서비스에 둔다. `OrderCommandHandler`는 검증 순서와 주문 생성 호출만 조율한다.

### 4.2 주문 생성 흐름

`OrderCommandHandler.createOrder`의 처리 순서는 다음과 같다.

1. 주문 생성 command의 형식과 중복 상품 ID를 검증한다.
2. Product Service에서 요청 상품의 주문 스냅샷을 조회한다.
3. 스냅샷 수, 상품 ID, 판매자 ID, 금액을 검증한다.
4. 구매자 ID와 각 스냅샷의 판매자 ID를 비교해 셀프 구매를 검증한다.
5. 요청 상품과 스냅샷을 `OrderItem`으로 결합한다.
6. `OrderCreator`를 호출해 주문 저장, 장바구니 정리, 이벤트 또는 Outbox 처리를 수행한다.

4단계에서 실패하면 `OrderCreator`를 호출하지 않는다. 따라서 주문·주문상품 저장, 장바구니 삭제, 무료 주문 Outbox 추가, 유료 주문 생성 이벤트 발행이 모두 발생하지 않는다.

직접 주문과 장바구니 기반 주문은 동일한 `POST /api/v2/orders` 및 Handler 흐름을 사용하므로 별도 분기 없이 모두 적용된다.

### 4.3 API 문서

`OrderController.createOrder`의 Swagger 설명에 다음 정책을 명시한다.

- 구매자와 판매자가 같은 상품은 주문할 수 없다.
- 여러 상품 중 하나라도 본인 상품이면 주문 전체가 실패한다.
- 실패 시 주문 저장과 장바구니·이벤트 변경이 발생하지 않는다.

`403` 응답 설명에는 `O015 본인 상품 구매 불가`를 추가한다. 기존에 기재된 `A004`는 이 주문 생성 API의 실제 내부 계약과 맞지 않으므로 `O015` 계약으로 교체한다.

## 5. 테스트 전략

### 정책 단위 테스트

- 구매자와 모든 판매자가 다르면 성공한다.
- 무료 본인 상품이면 `SELF_PURCHASE_NOT_ALLOWED`가 발생한다.
- 유료 본인 상품이면 동일한 오류가 발생한다.
- 다건 중 하나만 본인 상품이어도 동일한 오류가 발생한다.

### Handler 단위 테스트

- 본인 상품이 포함되면 `OrderCreator`를 호출하지 않는다.
- 일반 상품 주문은 기존과 동일하게 `OrderCreator`에 전달된다.
- 스냅샷 자체가 유효하지 않으면 셀프 구매 정책보다 기존 입력값 오류가 우선한다.

`OrderCreator`가 호출되지 않는 검증으로 Repository, Cart, Outbox, 이벤트에 진입하지 않음을 애플리케이션 경계에서 보장한다. 기존 `OrderCreatorTest`는 주문 저장·장바구니·무료 Outbox·유료 이벤트의 실제 부수효과가 모두 `OrderCreator` 내부에 있음을 계속 검증한다.

### Controller 계약 테스트

- Use Case가 `SELF_PURCHASE_NOT_ALLOWED`를 던지면 HTTP 403을 반환한다.
- 응답 `code`는 `O015`이고 메시지는 오류 계약과 일치한다.

### 회귀 검증

- 관련 정책·Handler·Controller 테스트
- `:order-service:test` 전체 테스트
- `git diff --check`

## 6. 영향과 호환성

- REST 성공 요청·응답 형식은 변경하지 않는다.
- 실패 응답에 새 주문 서비스 오류 코드가 추가된다.
- DB, Kafka topic·payload, gRPC 및 Redis 계약은 변경하지 않는다.
- 판매자 ID는 기존 Product Service 주문 스냅샷을 그대로 신뢰하므로 추가 원격 호출은 없다.
