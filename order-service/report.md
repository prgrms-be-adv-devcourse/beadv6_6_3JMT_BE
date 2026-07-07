# Order-Service 개선 리포트

> 본 문서는 order-service의 개선 필요 항목들에 대한 전체 리포트입니다. 기존에 완료된 P0 항목과 이전 브랜치 및 현재 브랜치(`refactor/#219-grpc-kafka-convention`, `refactor/#205-auto-cancel-unpaid-order`)에서 구현 완료된 항목들을 **해결 완료된 항목**으로 분류하여 정리해 둡니다.

## 1. 해결 완료된 항목

### 1.1 [P0] 인가 인터셉터 미등록
* **내용**: `WebConfig`에 인가 인터셉터 등록이 누락되어 인가가 처리되지 않던 문제
* **해결 요약**: `WebConfig` 내 인가 인터셉터 빈 등록 및 설정 완료.

### 1.2 [P0] gRPC 어댑터의 ON_SALE 하드코딩
* **내용**: 상품 서비스 호출 시 `ON_SALE` 상태만 강제하도록 하드코딩되어 있던 문제
* **해결 요약**: 상품 상태 파라미터를 동적으로 제어하거나 도메인 정책에 맞게 조회 조건을 유연화하여 수정 완료.

### 1.3 [P1] 미결제 주문 자동 만료 처리 (Redis 예약 및 스케줄러 배치) 구현
* **내용**: 주문 생성 후 일정 시간 동안 결제되지 않은 PENDING 주문이 방치되는 문제
* **해결 요약**:
  - 주문 생성 시 Redis Sorted Set(`order:expiration`)에 만료 시각(생성 시각 + 20분)을 기록.
  - `OrderExpirationWorker` 스케줄러가 주기적으로 만료 대상을 조회하여 만료 처리(`expirePending`)를 진행하고, 장바구니 상품 복원(`restoreCart`)을 수행하도록 구현.
  - 처리 실패 시 최대 3회 재시도 및 초과 시 DLQ(`order:expiration:dlq`)로 이동하도록 하여 시스템 복원력을 높임.

### 1.4 [P1] 결제 승인 전 주문 금액/만료 여부 동기 검증 API(validatePaymentReady) 도입
* **내용**: 결제 완료 처리를 할 때 금액이 일치하지 않거나 이미 만료된 주문이 결제되는 문제
* **해결 요약**:
  - PG사 결제 요청/승인 직전, 주문의 소유자, 상태, 만료 여부 및 요청 금액 일치 여부를 동기식으로 상호 검증하는 API `/api/v1/orders/{orderId}/payment-ready` 구현.
  - 금액 불일치 결제 승인을 사전에 방어함.
<<<<<<< HEAD

### 1.5 [P1] 결제 이벤트 Kafka 메시징 표준화 및 스키마 변경
* **내용**: 개별 결제 토픽들(`payment.approved`, `payment.refunded`)을 따로 리스닝하며 파싱 방식이 파편화되어 있던 문제
* **해결 요약**:
  - 단일 통합 토픽인 `payment.events`를 구독하는 구조로 단일화 및 표준화.
  - Envelope Pattern(메타데이터 `eventType` + `payload` 구조)으로 이벤트 스키마 파싱 방식을 표준화.
  - 대문자 ENUM 표준 명명 규격(`PAYMENT_APPROVED`, `PAYMENT_REFUNDED`, `PAYMENT_FAILED`, `PAYMENT_CANCELED`) 매핑 적용.
  - 처리가 필요 없거나 무관한 이벤트 타입(`PAYMENT_FAILED`, `PAYMENT_CANCELED`)은 DLT로 보내지 않고 Graceful하게 무시(`shouldIgnore`)하도록 필터링 적용.

### 1.6 [P2] gRPC 연동 메서드명 표준화 및 규격 준수
* **내용**: 판매자 다건 조회 시 proto 규격과 어댑터의 메서드명이 다른 곳과 일관되지 않던 문제
* **해결 요약**:
  - `seller_query.proto` 파일 및 `SellerGrpcClientAdapter.java` 에서 판매자 조회 메서드명을 `findSellers` 에서 표준 네이밍인 `getSellers` 로 변경 및 통일 완료.


---

## 2. 부분 해결된 항목

### 2.1 [P1] 결제-주문 불일치 비동기 보상 흐름 부재
* **현재 상태**: 
  - `validatePaymentReady` API를 통해 결제 승인 직전에 유효성 동기 검증을 수행하여, 비정상 결제가 진행되는 것을 1차적으로 사전에 차단합니다.
  - Kafka `DefaultErrorHandler`가 실패 메시지를 원본 토픽의 `.DLT`로 보내도록 설계되어 있습니다.
  - 이번 Kafka 메시징 표준화를 통해 잘못된 JSON 형식이나 payload 누락 등 유효하지 않은 결제 메시지가 `payment.events.DLT` 토픽으로 안전하게 이동하도록 처리 흐름을 보완했습니다.
* **남은 문제 (사후 비동기 검증 및 실패 대응)**:
  - `.DLT` 토픽을 실제로 소비하여 재처리하거나 로깅하는 전용 컨슈머 없음
  - 결제는 승인되었으나 주문 상태 변경(PAID) 처리 중 비동기 단계에서 최종 실패했을 때, `payment.cancel-requested` 같은 환불 요청 이벤트를 발행하는 자동 보상 흐름 없음
  - 수동 조치가 필요한 건에 대한 운영팀 알림(Slack, 이메일 등) 연동 없음
* **추가 개선 방안**:
  1. 결제 이벤트 DLT 컨슈머를 추가합니다.
  2. 금액 불일치 등 자동 취소가 가능한 실패 케이스는 결제 취소 요청 이벤트를 발행하여 자동 보상 처리합니다.
  3. 자동 보상이 불가능한 인프라 에러 등은 운영 알림을 발송하고, 재처리 이력을 로깅/저장할 수 있는 추적 구조를 둡니다.

### 2.2 [P3] 테스트 전략 보강
* **현재 상태**:
  - `WebConfig` 등록 여부 및 `BUYER` 권한 차단 등의 인증/인가 시나리오 검증
  - Redis 만료 큐 보관소, 스케줄러 워커, 도메인 만료 정책에 대한 Mock 단위 테스트 추가 (`RedisOrderExpirationStoreTest`, `OrderExpirationWorkerTest`, `OrderExpirationServiceTest` 등)
  - **임베디드 카프카(EmbeddedKafka) 기반 스프링 부트 통합 테스트 도입**: `PaymentEventConsumerIntegrationTest.java` 를 추가하여, 실제 EmbeddedKafkaBroker 환경에서 결제 이벤트 수신, JSON 파싱 오류 및 payload 누락 시의 DLT 전송 여부 등 메시징 인프라 연동을 통합 검증하도록 보강되었습니다.
* **남은 문제**:
  - `@SpringBootTest` 기반 실제 데이터베이스(PostgreSQL 등) 통합 테스트는 아직 없음
  - Testcontainers 기반 PostgreSQL + Kafka 실환경 테스트는 아직 없음
  - Gateway부터 order-service까지 이어지는 역할별 E2E 보안 시나리오 테스트는 아직 없음
* **추가 개선 방안**:
  1. `@SpringBootTest` + `@AutoConfigureMockMvc` 기반으로 실제 컨텍스트에서 인터셉터, 필터, 예외 핸들러 동작을 검증합니다.
  2. Testcontainers로 PostgreSQL, Kafka, Redis를 띄워 실제 환경과 유사한 DB 제약 및 이벤트 흐름을 통합 검증합니다.

---

## 3. 미해결 항목

### 3.1 [P1] 스케줄러 다중 인스턴스 안전성 부재 (OutboxRelay 및 만료 스케줄러)
* **현재 상태**:
  - `OutboxRelay.publishPendingEvents()` 및 `OrderExpirationWorker.processExpiredOrders()`가 스케줄러 기반으로 주기적으로 동작합니다.
* **남은 문제**:
  - 다중 인스턴스로 기동될 때 동일한 이벤트나 만료 대상을 여러 인스턴스가 동시에 조회하여 중복 발행/취소 처리할 수 있는 동시성 문제가 존재합니다.
  - ShedLock 같은 분산 스케줄러 단일 실행 보장 장치 없음
  - 데이터베이스 락(`FOR UPDATE SKIP LOCKED` 등) 또는 Redis 분산 락을 통한 획득 검증이 누락되어 있음
* **개선 방안**:
  1. Outbox 이벤트 및 만료 대상 조회에 비관적 락(Pessimistic Lock)과 `SKIP LOCKED` 전략을 적용하거나 Redis 락을 이용한 원자적 획득을 구현합니다.
  2. ShedLock을 도입하여 클러스터 환경에서 단 하나의 인스턴스에서만 스케줄러가 활성화되도록 제어합니다.

### 3.2 [P1] DB 제약 조건 누락 — 장바구니 레이스 컨디션
* **현재 상태**:
  - 도메인 레벨에만 상품 중복 추가 검사 및 주문 상품 ID 중복 검사가 있습니다.
* **남은 문제**:
  - `cart(buyer_id)` 유니크 제약 없음
  - `cart_product(cart_id, product_id)` 유니크 제약 없음
  - 주문 생성 멱등키 및 유니크 제약 조건 없음
  - 동시 요청 시 중복 장바구니 생성 또는 중복 PENDING 주문 다량 생성 위험 잔존
* **개선 방안**:
  1. `cart.buyer_id` 및 `cart_product(cart_id, product_id)` 테이블에 유니크 제약을 설정하는 마이그레이션(Flyway/Liquibase)을 추가합니다.
  2. 주문 생성 시 클라이언트가 발행한 멱등키를 활용하여 동일 요청 시 1회만 생성되도록 처리합니다.

### 3.3 [P2] 트랜잭션 경계 문제 — 원격 호출이 트랜잭션 내부
* **현재 상태**:
  - `OrderService`는 클래스 레벨 `@Transactional`을 사용하여 `createOrder()` 등의 메서드를 동작시킵니다.
  - `createOrder()` 내부에서 상품 정보 조회를 위해 gRPC/Feign 호출(`productClient.getOrderSnapshots()`)을 실행하고 있습니다.
* **남은 문제**:
  - 외부 통신(원격 호출)이 DB 트랜잭션 커넥션을 획득한 상태에서 동작하므로 외부 장애나 지연 발생 시 커넥션 풀이 고갈될 위험이 있습니다.
* **개선 방안**:
  1. 클래스 레벨 `@Transactional`을 제거하고 실제 DB 작업이 발생하는 저장/수정 메소드로 트랜잭션 범위를 좁힙니다.
  2. 외부 서비스 통신(gRPC, Feign)은 트랜잭션 밖에서 조회하여 데이터 스냅샷을 마련한 뒤, 생성된 스냅샷을 받아 DB에 저장하는 형태로 구현 영역을 분리합니다.

### 3.4 [P2] 죽은 코드·문서 불일치 정리
* **현재 상태**:
  - 주문 만료 에러 코드인 `ErrorCode.ORDER_EXPIRED(O015)`가 새로 추가되었습니다.
* **남은 문제**:
  - `ErrorCode.CART_EMPTY(O004)`, `ErrorCode.ORDER_PRICE_CHANGED(O011)`: 코드상 던져지는(throw) 지점 없음
  - `OrderReviewRequest`: 미사용 DTO
  - `Order.updateOrderStatus()`: 검증 없이 주문 상태를 직접 변경할 수 있는 위험한 세터 메서드 잔존
  - `Cart.recalculateTotalAmount()`: totalAmount를 0으로 리셋하는 부정확한 컬럼 구조 유지
  - 상품 중지/삭제 등의 이벤트가 실질적인 비즈니스 로직(장바구니 갱신 등)으로 이어지지 않고 로그만 출력됨
* **개선 방안**:
  1. 미사용 에러 코드와 DTO, 세터 메서드를 제거합니다.
  2. `Cart.totalAmount` 구조를 개선하거나 재계산 방식을 정교화하고, 상품 변경 이벤트를 실질적인 장바구니 동기화 로직에 연결합니다.

### 3.5 [P2] 코드 품질·스타일 이슈
* **남은 문제**:
  - 오타 메서드명 `searchOrderproducts` 유지 (여전히 호출되고 있음)
  - 일부 테스트 코드의 와일드카드 import
  - `OrderProduct`가 `BaseEntity`를 상속받지 않아 수동으로 시간 필드를 관리하는 구조
  - local/default 환경 설정 파일(`application.yml`)의 `ddl-auto: update` 활성화 상태 유지
* **개선 방안**:
  1. `searchOrderproducts` 오타를 카멜케이스 규격(`searchOrderProducts`)으로 수정하고 호출처를 갱신합니다.
  2. `OrderProduct`가 공통 `BaseEntity`를 상속받아 등록/수정 시각을 JPA Auditor를 통해 자동 관리하도록 일원화합니다.
  3. 로컬 환경에서도 가급적 DDL 자동 변경 방지 또는 테스트 구동 환경 분리를 명확히 합니다.

### 3.6 [P3] 보안 인프라 강화
* **현재 상태**:
  - Gateway가 전달하는 인증 헤더(`X-User-Id`, `X-User-Role`)를 기반으로 권한을 처리합니다.
  - 내부 서비스 간 gRPC 호출에는 plaintext가 그대로 사용되고 있습니다.
* **남은 문제**:
  - 내부 서비스 포트에 직접 접근할 수 있을 경우 헤더 위조 위험에 노출됩니다.
  - 내부 gRPC 호출 시 TLS/mTLS 암호화 및 신뢰성 검증 없음
* **개선 방안**:
  1. 서비스 게이트웨이 및 내부 서비스 간의 공유 시크릿 헤더(Shared Secret) 인증을 임시 보완책으로 도입합니다.
  2. gRPC TLS 설정을 활성화하거나 Kubernetes 환경인 경우 서비스 메시 기반의 mTLS 구축을 검토합니다.

---

## 4. 남은 개선 우선순위

```mermaid
gantt
    title Order-Service 남은 개선 로드맵
    dateFormat  YYYY-MM-DD
    section P1 — 단기
    결제 사후(비동기) 보상 흐름           :crit, p1-1, 2026-07-07, 5d
    스케줄러 다중 인스턴스 안전화 (Outbox+만료) :crit, p1-2, 2026-07-07, 3d
    DB 제약 조건 보강                   :crit, p1-3, 2026-07-07, 2d
    section P2 — 중기
    트랜잭션 경계 분리                  :p2-1, after p1-1, 3d
    죽은 코드·문서 정리                 :p2-2, after p1-1, 2d
    코드 품질·스타일 수정               :p2-3, after p2-2, 2d
    section P3 — 장기
    보안 인프라 강화                    :p3-1, after p2-1, 5d
    테스트 전략 보강                    :p3-2, after p2-1, 5d
```

---

## 5. 핵심 결론

P0 보안/상태 매핑 문제와 P1의 주문 만료 처리, 사전 금액 검증 API가 해결되었습니다. 남은 핵심 위험은 **결제 사후 비동기 보상 흐름**, **스케줄러의 다중 인스턴스 기동 시의 동시성 안전화(분산 락)** 및 **DB 제약 조건**입니다. 다음 단계에서는 P1 단기 과제부터 세부 이슈 및 브랜치로 분할하여 접근하는 것을 권장합니다.
