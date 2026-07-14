# OrderService CQRS 리팩터링 설계

## 배경

현재 `OrderService`는 주문 생성, 다운로드 확정, 결제 준비 검증, 주문 상세·콘텐츠·목록·결제 내역 조회를 모두 구현한다. 이 때문에 생성자 의존성이 11개에 이르고, 변경 이유가 다른 유스케이스들이 하나의 트랜잭션 경계와 테스트 클래스에 결합되어 있다.

이번 리팩터링은 API와 데이터 모델을 변경하지 않고 애플리케이션 계층에서 Command와 Query 책임을 분리한다. 단순히 기존 서비스를 두 클래스로 나누는 데 그치지 않고, 상태를 변경하는 Command는 유스케이스별 Handler로 분리한다.

## 목표

- `OrderService`의 서로 다른 변경 이유를 분리한다.
- Command와 Query의 트랜잭션 경계를 명확하게 만든다.
- 각 서비스가 실제로 사용하는 의존성만 주입받게 한다.
- 기존 HTTP API, 응답 형식, 도메인 규칙과 저장소 구현을 유지한다.
- 책임별 단위 테스트를 독립적으로 실행할 수 있게 한다.

## 범위에서 제외하는 항목

- Command와 Query용 물리 DB 또는 스키마 분리
- 별도 조회 모델이나 이벤트 기반 read model 구축
- Repository 인터페이스의 Command/Query 물리 분리
- API 경로 또는 응답 계약 변경
- presentation DTO를 application DTO로 이동하는 계층 정리
- 도메인 규칙이나 동시성 정책 변경

## 애플리케이션 구조

기존 `OrderUseCase`와 `OrderService`를 다음 세 경계로 교체한다.

```text
application/usecase/
├── CreateOrderUseCase.java
├── ConfirmDownloadUseCase.java
└── OrderQueryUseCase.java

application/service/order/
├── CreateOrderCommandHandler.java
├── ConfirmDownloadCommandHandler.java
└── OrderQueryService.java
```

프로젝트의 기존 `Service` 명명 관례를 유지하되, 상태 변경 유스케이스는 역할이 명확하도록 `CommandHandler` 접미사를 사용한다. 다운로드 유스케이스는 기존 메서드와 API 명칭에 맞춰 `ConfirmDownload`로 명명한다.

## 책임과 의존성

### CreateOrderCommandHandler

`CreateOrderUseCase`를 구현하고 `createOrder`만 제공한다.

주요 책임:

- 주문 생성 요청 및 상품 스냅샷 검증
- 주문번호 생성과 주문 애그리거트 구성·저장
- 주문된 상품의 장바구니 제거
- 주문 생성 Outbox 메시지 추가
- 주문 만료 예약용 애플리케이션 이벤트 발행
- 생성 결과 응답 변환

의존성:

- `OrderRepository`
- `CartRepository`
- `OrderNumberGenerator`
- `ProductClient`
- `OrderPolicyService`
- `OrderEventMessageFactory`
- `OutboxEventAppender`
- `ApplicationEventPublisher`

클래스 전체에 기본 쓰기 트랜잭션을 적용한다. 의존성 수는 여전히 8개이지만 모두 단일 주문 생성 트랜잭션을 완성하는 협력자다. 의존성 개수를 줄이기 위해 의미 없는 Facade로 감싸지 않는다.

### ConfirmDownloadCommandHandler

`ConfirmDownloadUseCase`를 구현하고 `confirmDownload`만 제공한다.

주요 책임:

- 주문 소유권과 주문상품 포함 여부 검증
- 결제 완료 상품인지 검증
- Product Service 콘텐츠 접근 가능 여부 확인
- `tryMarkDownloaded`를 통한 다운로드 상태의 원자적 확정
- 다운로드 및 환불 가능 여부 응답 반환

의존성:

- `OrderRepository`
- `OrderProductRepository`
- `ProductClient`

기존 경쟁 조건 처리와 예외 동작을 유지하며 쓰기 트랜잭션에서 실행한다.

### OrderQueryService

`OrderQueryUseCase`를 구현하고 클래스 전체에 `@Transactional(readOnly = true)`를 적용한다.

포함 메서드:

- `getOrderDetail`
- `getOrderContent`
- `validatePaymentReady`
- `getOrders`
- `getOrderPayments`

`validatePaymentReady`는 HTTP POST 엔드포인트이지만 주문 상태를 변경하지 않으므로 Query로 분류한다. `getOrderContent`도 외부 콘텐츠를 조회할 뿐 다운로드 상태를 변경하지 않으므로 Query다.

의존성:

- `OrderRepository`
- `OrderPaymentRepository`
- `ProductClient`
- `OrderPolicyService`
- `OrderExpirationPolicy`

## 요청 흐름

`OrderController`는 세 UseCase 인터페이스를 주입받는다.

```text
OrderController
├── CreateOrderUseCase
│   └── CreateOrderCommandHandler
├── ConfirmDownloadUseCase
│   └── ConfirmDownloadCommandHandler
└── OrderQueryUseCase
    └── OrderQueryService
```

각 엔드포인트의 경로, 요청 DTO, 응답 DTO와 HTTP 상태는 변경하지 않는다. Controller는 기존 메서드를 알맞은 UseCase로 위임하는 역할만 수행한다.

## 오류 및 동시성 처리

- 기존 `OrderException`과 `ErrorCode`를 그대로 사용한다.
- 주문 소유권, 결제 상태, 만료, 금액 불일치 검증 순서를 유지한다.
- 다운로드 확정 전에 Product Service 콘텐츠 접근 여부를 확인한다.
- 다운로드/환불 경쟁 조건은 기존 `OrderProductRepository.tryMarkDownloaded` 결과로 판단한다.
- 주문 생성의 저장, 장바구니 변경, Outbox 추가는 하나의 트랜잭션에 유지한다.

## 테스트 전략

기존 `OrderServiceTest`를 책임별로 분리한다.

- `CreateOrderCommandHandlerTest`: 주문 생성 및 검증, 장바구니 제거, Outbox·이벤트 발행
- `ConfirmDownloadCommandHandlerTest`: 권한·상태 검증, 콘텐츠 조회 실패, 중복 확정, 환불 경쟁 조건
- `OrderQueryServiceTest`: 주문 상세·콘텐츠·목록·결제 내역·결제 준비 검증

추가 변경:

- `OrderControllerTest`는 세 UseCase mock을 주입하도록 수정한다.
- `OrderWebConfigTest`는 변경된 Controller 생성자 의존성을 반영한다.
- `OrderCreationResilienceIntegrationTest`는 `CreateOrderCommandHandler`를 대상으로 유지한다.
- 전체 `:order-service:test`를 실행해 기존 동작이 보존되는지 검증한다.

## 완료 조건

- 기존 `OrderService`와 `OrderUseCase`가 제거된다.
- 모든 주문 API의 외부 계약이 유지된다.
- 각 새 구현체가 해당 책임에 필요한 의존성만 가진다.
- 조회 서비스의 모든 public 메서드가 read-only 트랜잭션에서 실행된다.
- 기존 주문 서비스 테스트가 책임별 테스트로 이전되고 모두 통과한다.
- `./gradlew :order-service:test`가 성공한다.
