# order-service AGENTS.md 개편 설계

## 1. 목적과 적용 범위

`order-service/AGENTS.md`를 `order-service/**` 작업에 적용되는 모듈 전용 지침으로 개편한다. 저장소 전체 설명은 작업에 필요한 최소 범위만 남기고, 실제 빌드 설정과 현재 구현 구조를 기준으로 에이전트가 안전하게 코드를 탐색·수정·검증할 수 있도록 한다.

문서의 설명과 규칙은 한글로 작성한다. 클래스명, 패키지명, 설정 키, 프로파일, 명령어, 기술명은 코드와 동일한 원문 표기를 유지한다.

## 2. 확인한 프로젝트 사실

- 저장소는 루트 `settings.gradle`과 `build.gradle`을 사용하는 Gradle Groovy DSL 멀티 프로젝트다.
- 실제 기준 버전은 Java 21, Spring Boot 4.1.0, Spring Cloud 2025.1.2다.
- `order-service`는 루트 설정에서 `common-module`을 의존하고 Spring Web, JPA, PostgreSQL, Kafka, Redis, OpenFeign, gRPC, QueryDSL, Resilience4j, springdoc-openapi를 사용한다.
- 모듈 내부에는 Gradle Wrapper가 없으므로 모듈 디렉터리에서는 `../gradlew :order-service:test`처럼 루트 Wrapper를 호출해야 한다.
- 기본 계층은 `presentation`, `application`, `domain`, `infra`, `global`이다.
- 현재 `domain/model`은 JPA Entity를 포함한다. 따라서 문서에서 “domain은 JPA에 의존하면 안 된다”를 현재 사실로 강제하지 않는다. 대신 새 코드에서 Web, Kafka, Redis, gRPC, Feign 같은 기술 세부사항이 `domain`으로 유입되는 것은 금지한다.
- Controller는 `application/usecase`를 의존한다. 생성·다운로드 같은 변경 작업은 Command Handler, 조회 작업은 Query Service로 분리된 CQRS 형태가 일부 적용되어 있다.
- `application/client`와 `domain/repository`가 포트를 정의하고 `infra` 어댑터가 이를 구현한다.
- `default/local` 프로파일은 상품 조회에 REST/Feign을 사용하고 판매자 조회에는 빈 결과 fallback을 사용한다. `dev/prod` 프로파일은 상품·판매자 gRPC 클라이언트를 사용한다.
- gRPC 계약은 저장소 루트 `grpc/order`, `grpc/product`, `grpc/user`에서 코드 생성 입력으로 사용된다.
- Kafka Consumer는 수동 ACK를 사용하며 실패 시 같은 파티션의 `.DLT`로 전달한다. 현재 고정 간격 재시도 횟수는 3회다.
- 주문 이벤트 발행은 Outbox를 사용하고, 수신 이벤트는 `eventId`와 `consumerGroup` 조합으로 처리 이력을 저장해 멱등성을 보장한다.
- Redis는 미결제 주문 만료 예약·재시도·DLQ 처리에 사용한다. Outbox Relay와 주문 만료 Worker는 스케줄러로 실행된다.
- 테스트는 Mockito 단위 테스트, `@DataJpaTest`, `@SpringBootTest`, Embedded Kafka 통합 테스트를 사용한다. 테스트 프로파일은 H2와 비활성화된 외부 인프라 설정을 사용한다.
- 현재 마이그레이션 디렉터리는 존재하지 않으므로 문서에서 Flyway/Liquibase가 이미 운영 중인 것처럼 서술하지 않는다.

## 3. AGENTS.md 구성

개편 문서는 다음 순서로 구성한다.

1. 문서 목적, 적용 범위, 지침 우선순위
2. 실제 기술 스택과 저장소/빌드 방식
3. `order-service`의 도메인 책임과 다른 서비스 경계
4. 실제 패키지 구조와 계층별 역할
5. 의존성 방향과 기존 예외 구조를 다루는 원칙
6. API, 인증 헤더, 공통 응답 및 예외 처리 규칙
7. JPA·QueryDSL·트랜잭션 규칙
8. Kafka·Outbox·멱등성 규칙
9. Redis 주문 만료 처리 규칙
10. REST/gRPC 프로파일과 장애 처리 규칙
11. 테스트 작성·실행 기준
12. 에이전트 작업 절차와 변경 완료 체크리스트
13. 금지사항

## 4. 핵심 규칙의 방향

### 계층과 의존성

- Controller는 Use Case만 호출하고 비즈니스 규칙을 두지 않는다.
- 새 외부 연동은 `application/client` 포트와 `infra` 어댑터로 분리한다.
- Repository 포트와 Spring Data 구현을 분리하는 현재 구조를 유지한다.
- 현재 존재하는 `application`의 presentation DTO 또는 infra payload 참조는 관련 작업 없이 일괄 리팩터링하지 않는다. 새 코드에서는 역방향 의존성을 추가하지 않는다.
- 도메인 상태 변경은 Entity의 의미 있는 메서드를 통해 수행하고 무검증 setter를 추가하지 않는다.

### 데이터와 트랜잭션

- 다른 서비스 데이터베이스나 스키마를 직접 조회하지 않는다.
- 조회는 `readOnly = true`, 상태 변경은 명시적 트랜잭션 경계를 사용한다.
- 원격 호출을 포함한 트랜잭션 범위는 최소화하고, 외부 호출 실패와 DB 변경의 일관성을 함께 검토한다.
- QueryDSL 조회는 현재 projection과 repository custom 구현 방식을 따른다.

### 메시징과 스케줄러

- Kafka payload로 JPA Entity를 발행하지 않고 명시적인 이벤트 payload를 사용한다.
- Consumer 변경 시 수동 ACK, 재시도, DLT, 멱등 처리, 트랜잭션 경계를 함께 검증한다.
- 주문 상태 변경과 이벤트 발행이 결합되면 기존 Outbox 흐름을 우선 사용한다.
- Scheduler 변경 시 다중 인스턴스 중복 실행 가능성과 재시도/DLQ 동작을 검토한다.

### API와 보안

- 구매자 API는 `X-User-Id`, `X-User-Role`을 사용하고 관리자 API는 `X-User-Role`을 검증하는 현재 Interceptor 구성을 유지한다.
- Gateway가 전달한 인증 헤더를 기준으로 처리하며 JWT 파싱이나 비밀키를 이 모듈에 추가하지 않는다.
- 요청·응답 DTO와 Swagger 문서를 실제 API와 함께 갱신한다.
- JPA Entity를 API 응답이나 Kafka payload로 직접 노출하지 않는다.

### 검증

- 변경 계층에 맞는 단위 테스트를 우선 추가한다.
- Repository/QueryDSL 변경은 `@DataJpaTest`, Kafka 흐름은 Embedded Kafka 또는 적절한 통합 테스트를 검토한다.
- 기본 검증 명령은 모듈 디렉터리 기준 `../gradlew :order-service:test`다.
- 빌드 설정이나 생성 코드에 영향을 주면 `../gradlew :order-service:build`까지 실행한다.

## 5. 문서 품질 기준

- 현재 구현과 다른 이상적 구조를 이미 적용된 규칙처럼 서술하지 않는다.
- 규칙은 에이전트가 행동을 결정할 수 있도록 구체적인 경로, 프로파일, 명령어를 포함한다.
- 저장소 전체 서비스 설명은 `order-service`의 경계를 이해하는 데 필요한 수준으로 제한한다.
- 반복되는 설명을 줄이고 작업 전·후 체크리스트를 제공한다.
- 변경되지 않은 사용자 작업 파일과 비밀정보를 보호하도록 명시한다.

## 6. 변경 범위

- 수정 대상: `order-service/AGENTS.md`
- 설계 기록: 이 문서
- 애플리케이션 코드, Gradle 설정, 런타임 설정은 변경하지 않는다.
- 기존 작업 트리의 `.gitignore` 수정 및 루트 문서 삭제 상태는 건드리지 않는다.

## 7. Git 전달 전략

- 관련 이슈는 `#329`이며 작업 브랜치는 `feature/#329-codex-setting`을 사용한다.
- `AGENTS.md`, 이 설계 문서, 구현 계획 문서를 하나의 문서화 목적 커밋으로 구성한다.
- 커밋 메시지는 `docs: order-service 에이전트 작업 지침 추가`를 사용한다.
- 기존 `.github/PULL_REQUEST_TEMPLATE.md` 파일은 수정하지 않고 PR 본문 형식으로 사용한다.
- PR 제목은 `[DOCS] order-service - 에이전트 작업 지침 추가`로 작성하고 대상 브랜치는 `develop`으로 한다.
- PR 본문에 `Closed #329`, 실행한 테스트, 문서 변경 범위, 코드·API·DB·Kafka 계약 변경이 없음을 명시한다.
- 사용자 소유의 `.gitignore` 변경과 루트 문서 삭제는 stage·commit·PR에서 제외한다.
