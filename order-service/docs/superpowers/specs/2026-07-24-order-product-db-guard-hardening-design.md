# 주문 상품 DB 멱등성 보강 설계

- 작성일: 2026-07-24
- 대상: `order-service`
- 관련 이슈: `#533`
- 관련 PR: `#535`
- 상태: 사용자 승인 완료

## 1. 배경

디지털 상품은 수량과 재고 차감 개념이 없으며, 구매자가 같은 상품을 동시에 여러 주문에서 결제 대기 상태로 만들 수 없어야 한다.

PR `#535`에는 다음 보호 장치가 이미 구현되어 있다.

- DB의 주문 상품 상태를 조회해 `PENDING`, `PAID`, `REFUND_REQUESTED` 상품의 재주문과 장바구니 추가 차단
- Spring Data Redis `SET NX` 기반의 `buyerId + productId` 임시 예약
- 결제 성공·실패·주문 만료 후 토큰 기반 Redis 예약 정리
- Redis 만료 등록이 유실돼도 DB의 오래된 `CREATED` 주문을 찾는 reconciliation

리뷰 결과 다음 보강이 필요하다.

1. Redis 유실·failover 상황에서도 중복 `PENDING` 행을 차단하는 DB 제약
2. Redis 호출 중 DB 트랜잭션과 커넥션을 점유하지 않는 경계
3. Redis 장애의 빠른 실패와 일관된 오류 변환
4. 멱등 처리와 만료 보상의 운영 지표
5. 중복된 TTL 기본값 제거
6. 결제 취소·실패 이벤트가 오지 않는 경우의 복구 계약 명시
7. 실제 PostgreSQL 제약과 동시성 검증
8. 기존 데이터와 RollingUpdate를 고려한 마이그레이션
9. 운영 적용 순서와 알려진 한계 문서화

## 2. 목표

- 동일한 `buyerId + productId`에 복수의 `PENDING` 주문 상품이 커밋되지 않게 한다.
- Redis는 커밋 전 경쟁을 빠르게 차단하고, DB는 커밋된 상태의 최종 방어선이 되게 한다.
- Redis 네트워크 지연 중 DB 커넥션을 점유하지 않는다.
- Redis와 DB 충돌을 기존 `O018` 계약으로 일관되게 변환한다.
- 결제 결과 이벤트가 누락돼도 DB timeout reconciliation으로 `CREATED` 주문을 `FAILED` 처리한다.
- 새 테이블 없이 기존 `order_product`를 사용한다.
- 기존 API, Kafka, gRPC 계약과 `FAILED -> COMPLETED` 지연 승인 정책을 유지한다.
- 기존 RollingUpdate 배포와 호환되는 스키마 확장 순서를 사용한다.

## 3. 비목표

- HTTP `Idempotency-Key`로 동일 요청의 응답을 재생하는 기능
- `PAYMENT_CANCELED` 이벤트 또는 신규 Kafka 계약 추가
- `payment-service`를 통한 지연 승인 결제의 자동 환불
- 주문 및 주문 상품 상태 모델의 전면 재설계
- 기존 중복 주문의 자동 삭제·병합
- 이번 배포에서 `buyer_id NOT NULL` 축소 단계까지 강제 적용
- `PAID`를 포함한 전역 단일 소유권 DB 제약

## 4. 핵심 불변식

### 4.1 주문 상품

- 신규 애플리케이션이 생성하는 모든 `OrderProduct`에는 부모 주문과 같은 `buyerId`가 저장된다.
- `buyerId`는 외부 요청이나 public setter로 변경하지 않는다.
- 동일한 non-null `buyerId + productId`에는 최대 하나의 `PENDING` 행만 존재한다.
- `FAILED` 또는 `REFUNDED` 상품은 다시 주문할 수 있다.
- `PAID`와 `REFUND_REQUESTED` 상품은 애플리케이션 정책 조회로 재주문과 장바구니 추가를 차단한다.

### 4.2 Redis와 DB의 역할

- Redis는 아직 커밋되지 않은 동시 요청을 빠르게 직렬화하는 임시 예약 저장소다.
- DB 조회는 구매 및 주문 상태의 최종 진실 공급원이다.
- PostgreSQL 부분 유니크 인덱스는 복수 `PENDING` 행의 최종 방어선이다.
- Redis 장애 시 중복 주문을 허용하지 않고 주문 생성과 장바구니 추가를 `SYS003`으로 실패시킨다.

## 5. 검토한 대안과 결정

### 5.1 별도 예약 테이블

`buyerId + productId` 전용 예약 테이블을 두면 상태와 수명을 명확히 모델링할 수 있다. 그러나 `order_product`에 `buyer_id`, `product_id`, `order_id`, 상태가 이미 존재하거나 추가될 예정이므로 같은 정보를 중복 관리하게 된다.

결정:

- 새 예약 테이블을 만들지 않는다.
- 기존 `order_product`에 부분 유니크 인덱스를 적용한다.

### 5.2 유니크 제약의 상태 범위

`PENDING`, `PAID`, `REFUND_REQUESTED` 전체를 유니크 범위로 잡으면 무료 주문과 단일 소유권까지 강하게 보호할 수 있다. 반면 현재 허용된 `FAILED -> PAID` 지연 승인과 새 주문이 경합할 때 어느 결제를 유지하고 어느 결제를 환불할지 결정해야 한다.

결정:

- 이번 변경은 원래 문제인 복수 `CREATED/PENDING` 주문 차단에 한정한다.
- 부분 유니크 인덱스의 조건은 `PENDING`만 사용한다.
- 지연 승인과 중복 결제 우선순위는 `payment-service`와의 후속 정책으로 분리한다.

### 5.3 트랜잭션 경계

검토한 대안:

1. Redis 예약을 DB 트랜잭션 밖에서 획득하고 별도 Spring Bean의 내부 트랜잭션을 호출
2. 현재처럼 하나의 트랜잭션 안에서 DB 조회와 Redis 호출 수행
3. PostgreSQL `SERIALIZABLE` 격리 수준으로 전체 주문 생성 직렬화

결정:

- 1번을 사용한다.
- Redis 지연 중 DB 커넥션을 점유하지 않는다.
- DB 제약으로 필요한 범위만 보호하며 전역 직렬화는 도입하지 않는다.

### 5.4 Redis Circuit Breaker

Redis 장애 시 Circuit Breaker와 자동 재시도를 추가하면 빠른 차단이 가능하지만 요청 지연, 재시도 폭증, 상태 복잡도가 함께 증가한다.

결정:

- 이번 변경에서는 명시적인 연결·명령 timeout과 fail-closed 정책을 사용한다.
- 요청 경로 자동 재시도와 Circuit Breaker·Bulkhead는 도입하지 않는다.
- 운영 지표를 수집한 뒤 필요성이 확인되면 후속 적용한다.

## 6. 데이터 모델과 마이그레이션

### 6.1 `OrderProduct.buyerId`

`OrderProduct`에 다음 영속 필드를 추가한다.

```java
@Column(name = "buyer_id", columnDefinition = "uuid")
private UUID buyerId;
```

초기 확장 단계에서는 RollingUpdate 호환성을 위해 물리 컬럼과 JPA 매핑을 nullable로 유지한다. 신규 도메인 객체에는 반드시 값이 들어가도록 `Order#addOrderProduct`가 다음 두 값을 함께 할당한다.

- 부모 `Order` 연관관계
- 부모 주문의 `buyerId`

`OrderProduct.create`는 구매자 ID를 인자로 받지 않는다. 구매자와 부모 주문이 어긋나는 객체를 만들 수 없게 한다.

### 6.2 현재 배포의 확장 마이그레이션

신규 Flyway 마이그레이션은 다음 순서로 수행한다.

1. `order_product.buyer_id`가 없으면 nullable `uuid` 컬럼으로 추가한다.
2. 기존 null 값을 부모 `"order".buyer_id`로 백필한다.
3. 기존 컬럼이 있으면 타입이 `uuid`인지 확인한다.
4. 동일한 non-null `buyer_id + product_id`의 중복 `PENDING` 데이터가 있는지 확인한다.
5. 중복이 없을 때 부분 유니크 인덱스를 생성한다.

인덱스 계약:

```text
이름: uk_order_product_buyer_product_pending
키: buyer_id, product_id
조건: buyer_id IS NOT NULL AND order_product_status = 'PENDING'
```

JPA의 `@UniqueConstraint`는 부분 조건을 표현할 수 없으므로 인덱스는 Flyway DDL에서만 관리한다. 런타임 주문 로직은 네이티브 SQL을 사용하지 않고 Spring Data JPA를 사용한다.

### 6.3 기존 중복 데이터

- 중복 행을 자동 삭제하거나 상태 변경하지 않는다.
- 마이그레이션 전에 중복을 운영 점검한다.
- 중복이 남아 있으면 인덱스 생성이 실패하게 해 배포를 중단한다.
- 운영자는 주문·결제 상태를 확인한 뒤 정리할 행을 결정한다.

### 6.4 RollingUpdate 호환성

현재 Kubernetes 설정은 `RollingUpdate`, `maxSurge: 1`, `maxUnavailable: 0`이다. 구버전 Pod와 신버전 Pod가 동시에 쓰는 동안 컬럼을 즉시 `NOT NULL`로 바꾸면 구버전 insert가 실패할 수 있다.

현재 배포:

- 컬럼 추가와 백필
- 신규 애플리케이션의 `buyer_id` 기록
- non-null 행에 대한 부분 유니크 인덱스 적용

후속 배포:

1. 모든 구버전 Pod가 제거됐는지 확인한다.
2. 배포 중 생성된 null 행을 다시 백필한다.
3. null 행이 없음을 검증한다.
4. `buyer_id NOT NULL`을 적용한다.

실제 운영 스키마에 `buyer_id NOT NULL`이 이미 존재하면 컬럼 확장·축소는 생략하고 인덱스만 적용한다.

## 7. 주문 생성 구조

### 7.1 외부 조정 계층

기존 `OrderCreator`는 비트랜잭션 조정 계층이 된다.

1. 주문 aggregate를 만들고 `orderId`를 확보한다.
2. 정렬·중복 제거한 상품 ID로 Redis 예약을 획득한다.
3. 별도 Bean인 주문 생성 트랜잭션 서비스를 호출한다.
4. 내부 서비스가 실패하면 현재 `orderId`가 소유한 Redis 예약을 해제한다.
5. 해제에 실패해도 원래 DB·비즈니스 예외를 보존한다.

별도 Spring Bean을 사용해 self-invocation으로 `@Transactional` 프록시가 우회되지 않게 한다.

### 7.2 내부 트랜잭션 계층

신규 내부 서비스는 `@Transactional` 경계를 소유한다.

1. DB에서 `PENDING`, `PAID`, `REFUND_REQUESTED` 차단 상태를 다시 확인한다.
2. 주문과 주문 상품을 `saveAndFlush`한다.
3. 장바구니에서 주문 상품을 제거한다.
4. 무료 주문이면 주문 완료 Outbox와 예약 정리 이벤트를 기록한다.
5. 유료 주문이면 기존 주문 생성 이벤트를 발행한다.
6. 하나의 상품이라도 충돌하면 주문 전체와 장바구니 변경을 롤백한다.

Redis 획득 이후 DB 상태를 다시 확인하므로 Redis 획득 전후에 커밋된 구매 상태도 차단한다.

### 7.3 저장과 제약 위반 변환

`OrderRepository`에 명시적인 `saveAndFlush` 포트를 추가한다. 영속성 어댑터는 PostgreSQL/Hibernate 원인 체인에서 제약 이름을 확인한다.

- `uk_order_product_buyer_product_pending` 충돌: `ORDER_PRODUCT_ALREADY_OWNED`, `O018`, HTTP 409
- 다른 unique, FK, NOT NULL 및 무결성 오류: 기존 예외를 그대로 전달

`saveAndFlush`로 제약 검사를 트랜잭션 메서드 내부에서 발생시켜 `O018` 변환과 전체 롤백을 보장한다.

## 8. Redis 예약 수명과 오류 처리

### 8.1 예약 획득

- Key: 기존 `order:product:idempotency:{buyerId}:{productId}`
- Value: 생성할 `orderId`
- 획득: 기존 Spring Data Redis `setIfAbsent`
- 여러 상품: 정렬된 순서로 획득
- 일부 충돌: 이번 요청이 획득한 키만 토큰 비교 후 해제

### 8.2 명시적 보상

현재 `OrderProductReservationService`의 트랜잭션 동기화 의존성을 제거하고 예약 획득과 실패 보상을 명시적으로 분리한다.

- Redis 충돌: `O018`
- Redis 획득·조회 장애: `SYS003`
- DB 검증·저장·장바구니·이벤트 실패: 현재 주문의 Redis 예약 해제 후 원래 예외 재발생
- Redis 해제 실패: 경고 로그와 실패 지표 기록, 원래 예외 보존, TTL로 최종 정리
- 프로세스가 Redis 획득 직후 종료: TTL로 복구

유료 주문이 커밋되면 결제 승인·실패 또는 timeout까지 예약을 유지한다. 무료 주문과 상태가 확정된 주문은 기존 `AFTER_COMMIT` 정리 흐름을 사용한다.

### 8.3 timeout 설정

권장 기본값:

- Redis 연결 timeout: 1초
- Redis 명령 timeout: 2초

두 값은 Spring Redis 설정과 환경변수로 외부화한다. 구체적인 property 이름은 현재 Spring Boot 버전의 configuration metadata로 구현 시 검증한다.

### 8.4 TTL 단일 출처

현재 annotation 기본값과 런타임 최솟값에 중복된 `30분`을 하나의 설정 기본값으로 통합한다.

- `productIdempotencyTtlMinutes > paymentTimeoutMinutes`
- 0 이하 값은 애플리케이션 시작 시 거절
- 임의의 `Math.max(..., 30)` 보정은 제거

## 9. 결제 실패·취소 누락과 주문 만료

`PAYMENT_FAILED` 이벤트가 오면 기존 보상 트랜잭션을 유지한다.

1. 이벤트 멱등성 확인
2. 주문과 주문 상품 잠금
3. `CREATED -> FAILED`
4. `PENDING -> FAILED`
5. 장바구니 복원
6. 처리 이력과 상태 커밋
7. 만료 예약과 상품 예약 정리

`PAYMENT_CANCELED` 이벤트는 추가하지 않는다. 결제 취소, 결제 실패 이벤트 누락, Redis 만료 등록 유실은 DB reconciliation으로 복구한다.

- `OrderStatus.CREATED`
- `createdAt <= now - paymentTimeout`
- 오래된 주문부터 batch 조회
- Redis 후보와 DB 후보를 합쳐 중복 제거
- Redis 조회 실패 시에도 DB 후보 처리 지속
- 다중 인스턴스는 기존 주문 비관적 락과 상태 전이 멱등성으로 보호

## 10. 관측 가능성

애플리케이션 계층에는 지표 기록 인터페이스만 두고 Micrometer 구현은 인프라 계층에 둔다. 도메인 모델은 Micrometer에 의존하지 않는다.

권장 지표:

| 지표 | 타입 | 태그 |
|---|---|---|
| `order.product.reservation.attempts` | Counter | `outcome=success/conflict/error` |
| `order.product.reservation.redis.duration` | Timer | `operation=acquire/exists/release`, `outcome=success/error` |
| `order.expiration.candidates` | Counter | `source=db/redis` |
| `order.expiration.compensation` | Counter | `outcome=success/skipped/failure/dlq` |

`buyerId`, `productId`, `orderId`, `eventId`는 지표 태그로 사용하지 않는다. 이 값들은 문제 추적용 구조화 로그에만 기록한다.

로그 기준:

- 주문 생성 실패: `orderId`, 처리 단계, 예외 종류
- Redis 정리 실패: `orderId`, 작업 종류
- 만료 보상: `orderId`, 후보 출처, 결과
- 결제 이벤트: 기존 `eventId`, `eventType`, `consumerGroup`
- 결제 비밀값과 토큰은 기록하지 않음

## 11. 실패 시나리오

| 상황 | 외부 동작 | 내부 복구 |
|---|---|---|
| Redis 키 충돌 | `O018`, 409 | 이번 요청이 획득한 다른 키 해제 |
| Redis 획득·조회 장애 | `SYS003`, 503 | DB 트랜잭션 시작 안 함 |
| DB 차단 상태 발견 | `O018`, 409 | 트랜잭션 롤백, Redis 예약 해제 |
| DB 부분 유니크 충돌 | `O018`, 409 | 트랜잭션 롤백, Redis 예약 해제 |
| 다른 DB 무결성 오류 | 기존 오류 | 전체 롤백, Redis 예약 해제 |
| Redis 보상 해제 실패 | 원래 오류 유지 | 로그·지표, TTL 정리 |
| 결제 실패 이벤트 누락 | 즉시 변화 없음 | timeout 후 DB reconciliation |
| Redis 만료 후보 조회 실패 | worker 지속 | DB 후보 처리 |
| 여러 상품 중 하나 충돌 | 전체 요청 실패 | 주문·장바구니 전체 롤백 |

## 12. 테스트 전략

사용자 요청에 따라 TDD 순서를 사용하지 않는다. 구현 후 다음 회귀 테스트를 추가한다.

### 12.1 도메인·단위 테스트

- `Order#addOrderProduct`가 `order`와 `buyerId`를 함께 할당
- Redis 충돌의 `O018` 변환
- Redis 장애의 `SYS003` 변환
- DB 실패 시 명시적 Redis 해제
- Redis 해제 실패 시 원래 예외 보존
- TTL 단일 기본값과 유효성 검증
- Micrometer outcome·operation 태그 검증
- 지표에 고카디널리티 ID가 없는지 검증

### 12.2 트랜잭션 테스트

- Redis 획득 시 실제 DB 트랜잭션이 비활성 상태
- 내부 저장 서비스에서만 트랜잭션 활성화
- 내부 서비스 호출 실패 시 외부 조정 계층이 예약 해제
- 여러 상품 중 하나의 실패가 전체 주문과 장바구니를 롤백
- 지정한 인덱스 충돌만 `O018`로 변환

### 12.3 PostgreSQL Testcontainers 테스트

현재 `order-service`에 이미 있는 PostgreSQL Testcontainers 의존성을 사용한다.

- V1부터 신규 마이그레이션까지 실행
- 기존 `order_product`의 `buyer_id` 백필
- 같은 구매자·상품의 두 번째 `PENDING` insert 실패
- 다른 구매자 또는 다른 상품은 성공
- 첫 주문 상품이 `FAILED`로 전이된 뒤 새 `PENDING` insert 성공
- 두 트랜잭션이 동시에 같은 구매자·상품을 저장할 때 하나만 성공
- 실제 제약 이름이 애플리케이션에서 `O018`로 변환

H2는 일반 JPA 회귀 테스트에 유지하되 PostgreSQL 부분 인덱스의 근거로 사용하지 않는다.

### 12.4 만료·장바구니 테스트

- 결제 결과 이벤트 없이 DB timeout으로 `CREATED -> FAILED`
- timeout 시 `PENDING -> FAILED`, 장바구니 복원, Redis 예약 정리
- Redis 후보 조회 실패에도 DB reconciliation 지속
- `PENDING`, `PAID`, `REFUND_REQUESTED` 상품 장바구니 추가 차단
- `FAILED`, `REFUNDED` 상품 재주문 허용
- 신규 `PAYMENT_CANCELED` 라우팅이 추가되지 않음

### 12.5 최종 검증

```bash
../gradlew :order-service:test --rerun-tasks
../gradlew :order-service:build
git diff --check
```

실제 테스트 수와 결과는 PR 본문에 갱신한다.

## 13. 배포 및 롤백

### 13.1 배포 전

- 실제 `order_product.buyer_id` 존재 여부, 타입, nullability 확인
- 기존 null 행 수 확인
- 동일 `buyer_id + product_id`의 중복 `PENDING` 데이터 확인
- 인덱스 생성 시간과 테이블 쓰기 잠금 영향 확인
- 중복 데이터가 있으면 자동 배포 중단

### 13.2 배포 중

- Flyway가 컬럼 확장·백필·인덱스를 적용
- 신규 Pod는 `buyer_id`를 기록
- 구버전 Pod의 insert는 nullable 컬럼 덕분에 계속 동작
- Redis 장애율, `SYS003`, `O018`, DB 제약 충돌 지표 관찰

### 13.3 배포 후

- null `buyer_id` 재점검 및 백필
- 복수 `PENDING` 행이 없는지 확인
- 신규 인덱스 사용과 오류율 확인
- 후속 배포에서 `buyer_id NOT NULL` 적용

애플리케이션 롤백 시 nullable 컬럼과 추가 인덱스는 구버전과 호환된다. 장애 중 인덱스를 자동 삭제하거나 데이터 상태를 되돌리지 않는다.

## 14. 예상 변경 범위

주요 수정 대상:

- `domain/model/Order.java`
- `domain/model/OrderProduct.java`
- `domain/repository/OrderRepository.java`
- `application/service/order/OrderCreator.java`
- 신규 주문 생성 트랜잭션 서비스
- `application/service/order/OrderProductReservationService.java`
- `infra/persistence/order/OrderAdapter.java`
- `infra/persistence/order/OrderPersistence.java`
- Redis 설정과 멱등·만료 지표 구현
- `OrderExpirationWorker`
- 신규 Flyway 마이그레이션
- 관련 단위·트랜잭션·PostgreSQL 통합 테스트
- PR 설명과 운영 영향 문서

변경하지 않는 범위:

- 신규 DB 테이블
- REST 요청·응답 형식
- Kafka topic, `eventType`, payload
- gRPC/Protobuf 계약
- `payment-service` 내부 구현

## 15. 완료 기준

- Redis 획득 중 DB 트랜잭션과 커넥션을 점유하지 않는다.
- 동일한 non-null `buyerId + productId`에 복수 `PENDING` 행이 저장되지 않는다.
- Redis 충돌과 DB 부분 유니크 충돌은 `O018`이다.
- Redis 획득·조회 장애는 `SYS003`이다.
- DB 처리 실패 후 Redis 예약을 해제하며 해제 실패가 원래 예외를 숨기지 않는다.
- 결제 결과 이벤트가 없어도 timeout 후 주문과 주문 상품이 `FAILED` 처리된다.
- 장바구니 복원과 예약 정리가 수행된다.
- 운영 지표에 고카디널리티 ID 태그가 없다.
- PostgreSQL 통합 테스트, 전체 `order-service` 테스트와 build가 통과한다.
- 새 테이블과 Kafka·gRPC 계약 변경이 없다.
- RollingUpdate 중 구버전 쓰기 호환성이 유지된다.

## 16. 알려진 한계와 후속 작업

### 16.1 무료 주문

무료 주문은 저장 전에 즉시 `PAID`가 되므로 `PENDING` 부분 유니크 인덱스의 직접 보호 대상이 아니다. 정상 경로에서는 Redis 예약과 DB 구매 상태 재검증이 중복을 차단한다.

무료 상품까지 DB에서 전역 단일 소유권으로 강제하려면 `PAID`를 포함하는 별도 정책 결정이 필요하다.

### 16.2 지연 승인

현재 도메인은 `FAILED -> COMPLETED`를 허용한다. 실패 주문 뒤 새 주문이 생성된 상태에서 이전 승인 이벤트가 도착하면 두 결제 중 어느 것을 유지할지 결정해야 한다.

후속 작업은 다음 서비스 간 정책을 포함해야 한다.

- 승인 이벤트의 시간 우선순위
- 이미 다른 활성 주문이 있을 때의 결제 환불
- 환불 요청·결과 이벤트와 Outbox 계약
- 사용자에게 노출할 주문·결제 상태

이 정책 없이 `PAID`까지 DB 유니크 범위를 확장하지 않는다.

### 16.3 `buyer_id NOT NULL`

이번 배포는 확장 단계다. 모든 구버전 Pod가 제거되고 null 백필이 완료된 다음 배포에서 `NOT NULL` 축소 마이그레이션을 수행한다.
