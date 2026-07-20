# AGENTS.md

## 1. 문서 목적과 적용 범위

이 문서는 `order-service/**`에서 코드를 탐색·수정·테스트·문서화하는 에이전트가 따라야 할 지침이다. 저장소 전체 규칙 가운데 `order-service` 작업에 필요한 내용만 포함한다.

- 시스템·개발자·사용자의 직접 지침을 이 문서보다 우선한다.
- 더 하위 경로에 별도의 `AGENTS.md`가 있으면 해당 경로에서는 더 가까운 문서를 함께 적용한다.
- 지침이 충돌하거나 요청 범위를 크게 바꿀 판단이 필요하면 임의로 확장하지 말고 사용자에게 확인한다.
- 작업 전 실제 코드, 테스트, 설정을 읽고 현재 구조와 명명 방식을 보존한다.
- 요청과 무관한 리팩터링, 포맷 변경, 파일 정리는 함께 수행하지 않는다.

## 2. 실제 프로젝트 기준

이 저장소는 루트의 `settings.gradle`, `build.gradle`, Gradle Wrapper를 사용하는 Groovy DSL 멀티 프로젝트다. `order-service` 내부에는 별도 Wrapper나 `settings.gradle`이 없다.

현재 기준 기술은 다음과 같다.

- Java 21
- Spring Boot 4.1.0
- Spring Cloud 2025.1.2
- Gradle Groovy DSL
- Spring Web MVC, Spring Data JPA, QueryDSL
- PostgreSQL, H2 테스트 데이터베이스
- Apache Kafka, Redis
- gRPC, Protobuf
- Resilience4j CircuitBreaker·Bulkhead
- springdoc-openapi
- JUnit 5, Mockito, Embedded Kafka

`common-module`은 루트 빌드에서 `implementation project(':common-module')`로 연결한다. 공통 이벤트 봉투, 응답 DTO, 공통 예외처럼 이미 공유하기로 한 타입만 사용하고 `order-service` 전용 비즈니스 타입을 `common-module`로 이동하지 않는다.

### Gradle 명령

`order-service` 디렉터리에서 실행할 때:

```bash
../gradlew :order-service:test
../gradlew :order-service:build
../gradlew :order-service:test --tests "com.prompthub.order.SomeTest"
```

저장소 루트에서 실행할 때:

```bash
./gradlew :order-service:test
./gradlew :order-service:build
```

전체 저장소 빌드는 요청 범위에 다른 모듈까지 포함되거나 공통 계약의 영향 확인이 필요할 때만 수행한다.

## 3. order-service 책임과 서비스 경계

`order-service`가 소유하는 책임은 다음과 같다.

- 구매자 장바구니 조회와 상품 추가·삭제
- 주문 생성, 상세·목록 조회, 결제 준비 상태 검증
- 구매·결제 내역과 관리자 주문·거래 통계 조회
- 결제 완료 콘텐츠 접근과 다운로드 여부 기록
- 미결제 주문 만료와 장바구니 복원
- 결제 승인·실패·취소·환불 이벤트 반영
- 주문 이벤트 Outbox 저장과 Kafka 발행
- 다른 서비스가 사용하는 주문 조회 gRPC 서버

서비스 경계는 다음과 같이 유지한다.

- 상품 스냅샷과 콘텐츠는 `ProductClient` 포트를 통해 `product-service`에서 조회한다.
- 판매자 정보는 `SellerClient` 포트를 통해 조회한다.
- 결제 결과는 `payment-service`가 발행한 Kafka 이벤트를 통해 반영한다.
- Toss Payments 승인·취소 API를 `order-service`에서 직접 호출하지 않는다.
- 다른 서비스의 내부 Java 클래스를 import하지 않는다. 공유 계약은 `common-module`, Protobuf 또는 명시적인 이벤트 스키마를 사용한다.
- 다른 서비스의 데이터베이스 테이블·스키마를 직접 조회하거나 변경하지 않는다.
- 다른 서비스가 소유한 Redis Key를 읽거나 변경하지 않는다.

## 4. 패키지 구조와 역할

기본 패키지는 `com.prompthub.order`이며 현재 구조를 따른다.

```text
com.prompthub.order
  presentation/           HTTP Controller와 요청·응답 DTO
  application/
    usecase/              presentation이 사용하는 유스케이스 계약
    service/              트랜잭션 경계와 유스케이스 구현
    client/               외부 서비스 조회 포트
    dto/                   애플리케이션 내부 조회 결과와 스냅샷
    event/                 애플리케이션 이벤트
  domain/
    model/                주문·장바구니·결제·Outbox 도메인 모델
    enums/                주문과 결제 상태
    repository/           영속성 포트
  infra/
    persistence/          JPA·QueryDSL 구현과 어댑터
    messaging/kafka/      Kafka Consumer·Producer·설정·payload
    redis/                주문 만료 저장소와 Worker
    rest/                 local/default REST 어댑터
    grpc/                 dev/prod gRPC Client와 주문 gRPC Server
  global/
    exception/            서비스 예외와 예외 응답 처리
    web/                  인증 헤더와 웹 공통 설정
```

### 의존성 원칙

- `presentation`은 `application/usecase`를 호출한다. Controller에 비즈니스 규칙이나 Repository·Kafka·Redis 접근을 넣지 않는다.
- `application/service`는 유스케이스를 조율하고 트랜잭션 경계를 정의한다.
- 새로운 외부 서비스 연동은 `application/client` 포트를 먼저 정의하고 `infra`에 어댑터를 구현한다.
- `domain/repository`는 영속성 포트를 정의하고 `infra/persistence`의 Adapter가 Spring Data Repository를 사용해 구현한다.
- 도메인 상태는 `markPaid`, `expirePending`, `markDownloaded`처럼 의미가 드러나는 메서드로 변경한다. 검증을 우회하는 public setter를 추가하지 않는다.
- JPA Entity를 Controller 응답 DTO나 Kafka payload로 직접 사용하지 않는다.

현재 `domain/model`은 JPA annotation을 사용하는 실용적인 구조다. 이를 JPA 비의존 도메인으로 이미 분리된 것처럼 가정하지 않는다. 다만 새 코드에서 Spring Web, Kafka, Redis, gRPC 같은 기술 세부사항을 `domain`에 추가하지 않는다.

기존 코드 일부에는 `application`이 presentation DTO 또는 infra Kafka payload를 참조하는 예외가 있다. 관련 리팩터링이 요청되지 않았다면 이를 일괄 수정하지 말고, 새 코드에서는 이런 역방향 의존성을 늘리지 않는다.

## 5. Command·Query와 애플리케이션 서비스

현재 주문 기능에는 CQRS 형태가 일부 적용되어 있다.

- 상태 변경 유스케이스는 `CreateOrderCommandHandler`, `ConfirmDownloadCommandHandler`처럼 목적이 분명한 Handler를 우선한다.
- 조회 유스케이스는 `OrderQueryService`, `AdminOrderQueryService`처럼 `@Transactional(readOnly = true)`를 사용한다.
- 단순히 파일 수를 맞추기 위해 Command와 Query를 기계적으로 분리하지 않는다. 기존 경계와 변경 목적을 따른다.
- 하나의 서비스가 여러 책임을 갖게 되면 유스케이스 단위로 분리하되 Controller가 구현체를 직접 의존하지 않게 한다.
- 정책 검증은 Controller나 Infra가 아니라 도메인 모델 또는 `application/service`의 정책 객체에 둔다.

## 6. API, 인증, 응답과 예외

외부 인증과 역할·상태 인가는 API Gateway가 담당하고, order-service는 Gateway가 전달한 신뢰된 사용자 ID를 사용한다.

- `/api/v1/orders/**`, `/api/v1/cart/**`: `X-User-Id` 필수. 애플리케이션 계층에서 주문·장바구니 소유권을 검증한다.
- `/api/v1/admin/**`: 관리자 역할·상태 검증은 Gateway의 책임이다. order-service 관리자 Controller는 역할 헤더를 읽거나 검증하지 않는다.
- 관리자 API의 401·403은 Gateway 또는 공통 예외 계약에 따라 외부에 노출될 수 있지만, order-service 내부 계약은 사용자 ID와 소유권 검증에 한정한다.

관련 구현은 `global/web/AuthHeaders`, Controller의 사용자 ID 바인딩, 애플리케이션 서비스의 소유권 검증, `GlobalExceptionHandler`에 있다.

- 비즈니스 서비스에서 JWT를 직접 파싱하거나 JWT 비밀키를 추가하지 않는다.
- 외부에서 전달된 사용자 ID를 사용하더라도 주문·장바구니 소유권 검증을 생략하지 않는다.
- 인증 헤더나 소유권 정책을 변경하면 웹 계약 테스트와 Controller 테스트를 함께 갱신한다.
- API는 `common-module`의 `ApiResult`, `PageResponse` 형식을 유지한다.
- 서비스 비즈니스 실패는 `global/exception/ErrorCode`, `OrderException`, `GlobalExceptionHandler` 흐름을 따른다.
- 도메인 예외에 HTTP 요청·응답 객체를 전달하지 않는다.
- 요청 DTO에는 Jakarta Validation을 사용하고, Swagger의 요청·응답·헤더·상태 코드 설명을 실제 동작과 함께 갱신한다.

## 7. 영속성, QueryDSL과 트랜잭션

- 주문·장바구니 데이터는 이 서비스가 소유한 Entity와 Repository를 통해서만 접근한다.
- Repository 인터페이스와 JPA 구현을 분리하는 현재 Adapter 패턴을 유지한다.
- 복잡한 목록·통계 조회는 기존 `Projection`, `*PersistenceCustom`, QueryDSL 구현 방식을 따른다.
- 조회 전용 서비스와 메서드는 `@Transactional(readOnly = true)`를 우선한다.
- 상태 변경은 원자적으로 처리해야 하는 범위에 `@Transactional`을 둔다. 이유 없이 Controller나 넓은 클래스 전체로 경계를 확대하지 않는다.
- 원격 REST/gRPC 호출을 DB 트랜잭션 내부에 추가하기 전에 지연·실패 시 커넥션 점유와 롤백 일관성을 검토한다. 가능하면 외부 조회와 DB 변경의 경계를 좁힌다.
- 이벤트 처리에서 주문 상태 변경, 결제 기록, 처리 이력, Outbox 저장이 함께 성공해야 한다면 하나의 트랜잭션으로 유지한다.
- Entity 연관관계, unique 제약, index, column 정의를 변경하면 기존 H2 PostgreSQL 호환 테스트와 실제 PostgreSQL 차이를 검토한다.
- 현재 모듈에는 Flyway/Liquibase 마이그레이션 체계가 초기화되어 있지 않다. 스키마 변경 시 `ddl-auto`만 변경해 해결하지 말고 마이그레이션 도입 여부와 배포 영향을 사용자에게 알린다.

## 8. 프로파일별 REST와 gRPC 연동

현재 프로파일별 구현 선택은 다음과 같다.

| 프로파일 | ProductClient | SellerClient |
|---|---|---|
| `default`, `local`, `dev`, `prod` | `ProductGrpcClientAdapter` | `SellerGrpcClientAdapter` |

- 모든 런타임 프로파일의 상품·판매자 조회는 gRPC를 사용한다.
- `dev/prod`의 gRPC 호출에는 설정된 deadline을 적용한다.
- 상품 gRPC 호출은 기존 Resilience4j CircuitBreaker·Bulkhead와 gRPC status-to-error 매핑을 유지한다.
- 호출 실패를 무조건 빈 결과로 바꾸지 않는다. 현재 상품 조회는 서비스 불가 예외를 전달하고, 판매자 닉네임 조회만 빈 결과 fallback을 사용한다.

Protobuf 계약은 저장소 루트의 다음 경로에 있다.

```text
../grpc/order
../grpc/product
../grpc/user
```

- 생성된 Protobuf·gRPC Java 파일을 직접 수정하지 않는다. `.proto` 또는 build 설정을 수정하고 다시 생성한다.
- 계약 변경 시 제공자와 소비자, 필드 호환성, 기본값, 배포 순서를 함께 검토한다.
- `order-service`가 소유하는 주문 조회 계약은 `../grpc/order`에 둔다. 다른 서비스가 소유한 계약은 임의로 이 모듈로 이동하지 않는다.

## 9. Kafka, Outbox와 멱등성

현재 Kafka Consumer는 manual ACK를 사용한다. 처리 실패 시 `DefaultErrorHandler`가 1초 간격으로 최대 3회 재시도한 뒤 원본 topic의 같은 partition에 해당하는 `.DLT`로 전달한다.

- 이벤트는 `EventMessage<T>` envelope와 명시적인 payload 클래스를 사용한다.
- JPA Entity나 API DTO를 이벤트 payload로 직접 발행하지 않는다.
- 이벤트 이름은 이미 발생한 비즈니스 사실을 나타내고, producer와 consumer가 합의한 `eventType`을 유지한다.
- Consumer는 필수 metadata와 payload를 검증한 뒤 Router·Handler·Processor로 위임한다.
- 지원하지 않는 결제 이벤트를 ACK만 하고 무시하는 현재 정책을 변경할 때는 DLT와 재처리 영향을 검토한다.
- 성공적으로 처리가 끝난 뒤에만 ACK한다. 예외를 삼켜 실패 메시지가 성공 처리되지 않게 한다.
- 수신 이벤트 멱등성은 같은 트랜잭션 안에서 `eventId`와 `consumerGroup` 조합을 확인하고 기록하는 현재 방식을 유지한다.
- 중복 이벤트에서도 상태 전이가 안전해야 하며, unique 제약 위반과 동시 처리 가능성을 테스트한다.
- 주문 상태 변경과 주문 이벤트 발행이 결합되면 기존 Outbox를 우선 사용한다.
- Outbox payload는 직렬화 가능한 계약 타입으로 만들고 발행 성공·실패·재시도 상태 전이를 보존한다.
- topic, key, `eventType`, payload 필드를 변경하면 생산자·소비자·DLT·재처리·하위 서비스 영향을 문서화한다.
- Kafka Consumer 또는 Outbox Relay를 수정하면 단위 테스트 외에 Embedded Kafka 통합 테스트 필요성을 검토한다.

## 10. Redis 주문 만료와 Scheduler

Redis는 미결제 주문의 만료 예약과 재시도를 관리한다.

- 만료 ZSet: `order:expiration`
- 재시도 Hash: `order:expiration:retry`
- 실패 목록: `order:expiration:dlq`

주문 생성 후 트랜잭션이 commit되면 `OrderExpirationRegistrar`가 만료 시각을 등록한다. `OrderExpirationWorker`는 만료 주문을 조회해 주문 취소와 장바구니 복원을 시도하고, 실패 횟수가 한도를 초과하면 DLQ로 이동한다.

- Redis 등록을 DB commit 이전으로 옮기지 않는다. 롤백된 주문이 예약되지 않도록 현재 `AFTER_COMMIT` 흐름을 유지한다.
- 결제 완료 시 만료 대상과 재시도 정보를 제거하는 흐름을 유지한다.
- Key를 추가할 때는 `order:` prefix를 사용하고 다른 서비스 Key와 충돌하지 않게 한다.
- 재시도 횟수, batch size, fixed delay, 결제 제한 시간은 하드코딩하지 않고 `prompthub.order.*` 설정을 사용한다.
- Scheduler나 batch 조회 방식을 변경할 때 여러 인스턴스의 중복 조회·중복 실행·락 전략을 반드시 검토한다.
- Outbox Relay도 Scheduler이므로 같은 다중 인스턴스 안전성 검토 대상이다.

## 11. 테스트 기준

변경한 계층과 위험에 맞는 가장 작은 테스트부터 실행한 뒤 필요한 범위로 확대한다.

- 도메인 상태와 정책: 순수 단위 테스트
- Application Service·Handler·Processor: Mockito 기반 단위 테스트와 상태 전이·협력 객체 검증
- Controller·Interceptor·예외 응답: 현재 MockMvc 또는 `@SpringBootTest` 패턴
- Repository·QueryDSL·JPA mapping: `@DataJpaTest`, `application-test.yml`의 H2 PostgreSQL mode
- Kafka 직렬화·수신·DLT: Embedded Kafka 통합 테스트
- gRPC Adapter·복원력 설정: in-process gRPC 또는 설정 단위 테스트
- Redis Store·Worker: mock 기반 저장소/Worker 테스트, 실제 원자성이 중요하면 통합 테스트 추가 검토

`application-test.yml`에서는 H2를 사용하고 Config Client·Eureka·Outbox Relay·주문 만료 Worker를 비활성화한다. 테스트가 외부 PostgreSQL, Redis, Kafka, Config Server에 우연히 의존하지 않게 한다. Embedded Kafka 테스트는 테스트가 명시적으로 broker를 제공해야 한다.

기능 변경 또는 버그 수정에는 정상 경로뿐 아니라 다음 실패 경로를 검토한다.

- 주문·장바구니 소유자 불일치 또는 사용자 ID 누락
- 잘못된 주문 상태 전이
- 금액·상품 스냅샷 불일치
- 중복 Kafka 이벤트
- 외부 REST/gRPC timeout과 unavailable
- 트랜잭션 롤백 시 Outbox·처리 이력 일관성
- Redis 재시도 한도와 DLQ 이동

## 12. 에이전트 작업 절차

### 작업 전

1. `pwd`, `git status --short`로 작업 위치와 사용자 변경사항을 확인한다.
2. 요청과 관련된 Controller, Use Case, Service, Domain, Adapter, 테스트, 설정을 함께 읽는다.
3. `rg`로 호출처, 이벤트 타입, 설정 키, 예외 코드, 테스트를 검색한다.
4. 변경이 REST/gRPC/Kafka/DB 계약에 미치는 서비스 간 영향을 확인한다.
5. 기존 사용자 변경과 겹치면 덮어쓰지 말고 범위를 조정하거나 사용자에게 알린다.

### 구현 중

1. 요청 범위를 만족하는 최소 변경을 한다.
2. 현재 naming, indentation, package 구조를 보존한다.
3. 비즈니스 규칙과 실패 조건을 먼저 테스트로 고정한다.
4. 로그에는 `orderId`, `eventId`, `eventType`, `consumerGroup`, `requestId` 등 추적 가능한 식별자를 사용하되 개인정보·토큰·결제 비밀값을 남기지 않는다.
5. 새로운 설정은 환경변수 또는 `@ConfigurationProperties`로 주입하고 비밀값을 기본값으로 넣지 않는다.

### 변경 후

1. 가장 가까운 테스트를 실행한다.
2. 기본 회귀 검증으로 `../gradlew :order-service:test`를 실행한다.
3. build 설정, Protobuf 생성, 패키징에 영향이 있으면 `../gradlew :order-service:build`를 실행한다.
4. `git diff --check`와 최종 diff를 검토한다.
5. API·DB·Kafka·gRPC 계약 변경이 문서와 테스트에 반영됐는지 확인한다.
6. `.env`, API Key, 토큰, 비밀번호, 인증서 등 민감정보가 포함되지 않았는지 확인한다.
7. 실행하지 못한 검증이 있으면 이유와 남은 위험을 결과에 명시한다.
8. 사용자가 요청하지 않은 stage, commit, push, PR 생성은 하지 않는다.

## 13. 금지사항

다음 작업은 명시적인 승인 없이 수행하지 않는다.

- 다른 서비스의 내부 클래스 import 또는 데이터베이스 직접 접근
- Controller에 비즈니스 규칙, Repository, Kafka Producer, Redis Client 주입
- JPA Entity의 API 응답·Kafka payload 직접 노출
- Gateway 인증 흐름 우회 또는 `order-service`에 JWT 비밀키 추가
- `payment-service`를 우회한 Toss Payments 직접 호출
- event payload, topic, gRPC 계약의 비호환 변경
- 멱등 처리 없이 Kafka Consumer 추가
- 주문 상태 변경과 이벤트 발행을 비원자적으로 분리
- 운영 설정에 secret 하드코딩
- Gradle Kotlin DSL 전환, 독립 Wrapper 추가, 별도 `settings.gradle` 생성
- 추가 공통 모듈 생성 또는 `common-module`에 서비스 전용 로직 이동
- 요청과 무관한 대규모 리팩터링·포맷팅·파일 삭제
- 실패하는 테스트를 숨기거나 검증 없이 완료로 보고
