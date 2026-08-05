payment-service 클린 아키텍처 규칙. 코드 배치·레이어·패키지·의존 방향 작업 시 따른다.

> 아래 패키지 구조는 **새 코드의 배치 계약**이다. 새 클래스를 추가할 때 반드시 이 구조를 따른다.
> 레이어 정의·의존 방향·Client/Gateway 네이밍 등 팀 공통 규칙은 루트 `../../../.claude/rules/clean-architecture.md`를
> 우선 따른다. 이 문서는 그중 payment-service에만 해당하는 구체화·추가 규칙만 기재한다.

## 패키지 구조

```
com.prompthub.paymentservice
├── domain
│   ├── model          ← 핵심 도메인 객체(Entity) 및 상태 enum
│   ├── event          ← 도메인 이벤트 (결제/환불 상태 변화, 순수 Java)
│   ├── repository     ← Repository 인터페이스 (도메인이 정의하는 영속성 계약)
│   └── exception      ← 도메인 불변 위반 예외
├── application
│   ├── usecase        ← UseCase 인터페이스(Input Boundary)
│   ├── service        ← UseCase 구현체 (비즈니스 흐름 조율)
│   ├── client
│   │   └── order      ← 내부 Order 서비스 호출 포트·계약 (예: OrderClient, OrderPaymentInfo)
│   ├── gateway
│   │   └── external   ← 제3자 API Gateway 인터페이스 (예: PaymentGateway)
│   ├── exception      ← API 에러 코드(HTTP 상태·코드 매핑), common-module ErrorCode 구현
│   └── dto
│       ├── command    ← 외부 → application 입력 (record 권장)
│       └── result     ← application → 외부 출력 (record 권장)
├── presentation       ← REST Controller, ExceptionHandler
│   ├── config         ← Swagger 등 presentation 계층 설정
│   └── dto
│       ├── request    ← HTTP 요청 DTO (@Valid 검증)
│       └── response   ← HTTP 응답 DTO
└── infrastructure
    ├── persistence    ← Spring Data JPA Repo, domain.repository 구현체, JPA Auditing 설정
    ├── grpc
    │   └── client
    │       └── order  ← 내부 Order gRPC 어댑터·채널 설정 (예: OrderGrpcClientAdapter)
    ├── external
    │   └── toss       ← 시스템 밖 Toss Payments 연동 (PaymentGateway 구현, ACL, Client)
    │       └── dto    ← Toss API 응답 역직렬화 DTO (패키지 외부 노출 금지)
    ├── scheduling     ← @Scheduled 주기 재처리 (환불 retry 등)
    └── messaging      ← 이벤트 발행 구현체 (@TransactionalEventListener), Kafka 설정
        ├── config     ← Kafka Producer/Consumer 빈 설정, 토픽 상수
        ├── dto        ← Kafka로 발행·구독하는 메시지 페이로드 DTO
        └── consumer   ← Kafka 컨슈머 (order-events 구독 입력 어댑터)
```

> **현재 코드와의 차이:** 기존 `application.gateway.external.OrderGateway`와
> `infrastructure.external.grpc.OrderGrpcClientAdapter`는 공용 규칙 통합 과정에서 남은 레거시 구조다.
> 규칙 문서 수정만으로 코드를 즉시 이동하지 않는다. 해당 경계를 리팩토링할 때 포트는
> `application.client.order.OrderClient`, 구현은
> `infrastructure.grpc.client.order.OrderGrpcClientAdapter`로 옮기고 이름을 맞춘다.

## 레이어별 핵심 규칙 (payment-service 고유)

트리 주석·루트 공통 규칙에 없는, payment-service 고유의 아키텍처 제약만 기재한다.

- **domain.model**: JPA 겸용 정책(`../../../.claude/rules/domain-model.md`)에 더해 SLF4J 로깅
  (`@Slf4j`, `log.debug/info` 등)도 상태 전이 추적 목적으로 허용한다 — payment-service 한정 추가 예외.
- **예외 패키지**: 도메인 불변 위반은 `domain.exception`, API 에러 코드(HTTP 상태·코드 매핑)는
  `application.exception`. common-module의 `ErrorCode`를 구현하는 enum도 `application.exception`에
  위치한다. ⚠ 루트 규칙은 `ErrorCode`·`GlobalExceptionHandler`를 횡단 관심사로 보고 `global/exception`에
  두도록 하지만, payment-service는 별도 `global` 계층 없이 `application.exception`과
  `presentation`(`PaymentExceptionHandler`)에 나눠 두고 있다 — 아직 루트 표준과 통일되지 않은 상태.
- **이벤트 발행**: Service 구현체는 `ApplicationEventPublisher.publishEvent(도메인이벤트)`로 Spring 내부
  이벤트를 발행한다. `infrastructure.messaging`의 구현체가 `@TransactionalEventListener(phase = AFTER_COMMIT)`
  으로 구독해 Kafka로 전달한다. `ApplicationEventPublisher`는 `spring-context` 추상 인터페이스이므로
  application 레이어 허용. AFTER_COMMIT이 핵심 — 트랜잭션 롤백 시 Kafka 메시지 발행이 차단된다.
- **스케줄러 예외**: `@Scheduled` 메서드 내부에서는 중첩 `@TransactionalEventListener` 제한(Spring Boot 4.1)
  으로 Spring 내부 이벤트를 쓰지 않는다. 대신 `TransactionSynchronizationManager.registerSynchronization()
  .afterCommit()`에서 `KafkaPaymentEventPublisher`를 직접 호출한다.
- **이벤트 구독(입력 어댑터)**: `infrastructure.messaging.consumer`의 `@KafkaListener`는 외부 이벤트를 받아
  애플리케이션 흐름을 개시하는 **입력 어댑터**다(presentation의 Controller와 같은 역할, 위치만
  infrastructure). 따라서 예외적으로 `application.usecase` 인터페이스에 의존할 수 있다(예:
  `OrderEventConsumer` → `RecordOrderSnapshotUseCase`). 메시지 파싱은 `StringDeserializer` +
  `ObjectMapper` 수동 파싱(order 도메인 계약 record라 타입 헤더 의존 불가). `@EnableKafka`는 명시하지
  않고 Spring Boot 자동설정에 맡긴다 — 그래야 `KafkaAutoConfiguration`을 제외한 JPA 슬라이스 테스트에서
  리스너 컨테이너가 기동되지 않는다.
- **presentation**: 요청 유효성 검증 규칙은 `api-error-handling.md` 참조.

## 데이터 흐름 예시 (결제 승인)

```
HTTP POST /payments/confirm
  → presentation.PaymentController
  → application.usecase.ConfirmPaymentUseCase (command 전달)
  → application.service.ConfirmPaymentService
      → application.client.order.OrderClient
          ← infrastructure.grpc.client.order.OrderGrpcClientAdapter
      → application.gateway.external.PaymentGateway
          ← infrastructure.external.toss.TossPaymentGateway
      → infrastructure.persistence.PaymentRepositoryAdapter (domain.repository.PaymentRepository)
      → ApplicationEventPublisher.publishEvent(PaymentApprovedEvent)  ← Spring 내부 이벤트
            ↓ [트랜잭션 커밋 후, @TransactionalEventListener AFTER_COMMIT]
        infrastructure.messaging.KafkaPaymentEventPublisher
            → KafkaTemplate.send("payment.approved", orderId, PaymentApprovedMessage)
  → application.dto.result.PaymentResult 반환
  → presentation.dto.response.PaymentResponse 변환
  → HTTP 200 응답
```
