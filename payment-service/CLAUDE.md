# payment-service

`beadv6_6_3JMT_BE` 모노레포(팀 3JMT) 안의 결제 MSA 서비스. **Spring Boot 4.1 / Java 21**, 클린 아키텍처 기반.

## 언어 정책

- 문서·커밋 메시지·코드 주석·테스트 메서드명: **한국어**
- 클래스/필드/메서드 식별자, 커밋 타입 접두사(`feat:` 등): **영어**
- AI 응답: **한국어**

## 빌드 / 실행 / 테스트

Gradle wrapper는 모노레포 루트(`../`)에 있으므로 모든 명령은 **payment-service 디렉터리에서** 실행한다.

```bash
../gradlew :payment-service:build                        # 빌드
../gradlew :payment-service:bootRun                      # 실행 (포트 8084)
../gradlew :payment-service:test                         # 전체 테스트
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.PaymentServiceApplicationTests"  # 단일
docker-compose up -d                                     # 로컬 PostgreSQL (호스트 5433) + Kafka (포트 9092)
```

- `docker-compose up -d`는 payment-service 디렉터리 내 `docker-compose.yml` 기준 (로컬 개발용 PostgreSQL + Kafka).
- 실행 전 `.env` 필요: `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `TOSS_SECRET_KEY` / `TOSS_TEST_MODE` (gitignore 대상, 커밋 금지).
  - `TOSS_SECRET_KEY` 미설정 시 더미 키(`test-dummy-key`)로 기동되어 실제 PG 연동 불가.
  - `TOSS_TEST_MODE=true` 설정 시 Toss 테스트 모드 활성화 (기본값 `false`).
- 테스트는 Testcontainers(PostgreSQL + Kafka)를 쓰므로 compose DB 없이 동작한다.
- `common-module`은 모노레포 서브프로젝트(`settings.gradle`에 `include 'common-module'`)로 포함되며, `project(':common-module')`로 의존한다. 모노레포 전체 체크아웃이 전제.
- common-module이 제공하는 타입: `BusinessException`, `ErrorCode`(에러 코드 인터페이스), 공통 응답 래퍼. 이 타입을 서비스 내부에서 재정의하지 않는다.
- OpenAPI 문서: `http://localhost:8084/v3/api-docs` (bootRun 기동 후). `springdoc-openapi-starter-webmvc-api`만 탑재되어 Swagger UI는 없음 — UI는 API Gateway에서 집계.
- Checkstyle은 루트 `build.gradle`에서 공유 룰(`style/checkstyle/prompthub-checkstyle-rules.xml`)을 자동 적용. 현재 `ignoreFailures = true`지만 위반 코드는 작성하지 않는다.
- Config Server(`http://localhost:8888`) 및 Eureka(`http://localhost:8761`)는 `optional` 설정으로 없어도 기동된다. 단, 서비스 메시 기능(설정 중앙화, 서비스 디스커버리)은 비활성화 상태가 된다.

## 의존성 관리

- 모노레포 루트 `build.gradle`(`../build.gradle`)이 공통 의존성/버전을 이미 관리한다. payment-service의 `build.gradle`에 새 의존성을 추가하기 전에 루트에 이미 선언되어 있는지 먼저 확인하고, 있다면 중복 선언하지 않는다.
- Spring Boot 4.1이 네이티브로 제공하는 기능은 서드파티 라이브러리로 직접 구현하지 않고 해당 스타터/기능을 우선 사용한다.
- 새 의존성이 필요하다고 판단되면, 루트에 없는지·Boot 4.1 네이티브 대체재가 없는지 먼저 확인한 뒤 추가한다. 판단이 애매하면 추가 전에 사용자에게 의도를 공유한다.

## 상세 규칙 (해당 작업 시 먼저 읽기)

아래 작업을 할 때는 반드시 해당 파일을 먼저 읽고 그 규칙을 따른다.

- **코드 배치·레이어·패키지 구조·의존 방향** → `.claude/rules/architecture.md`
- **REST 컨트롤러·예외 처리·API 응답 형식** → `.claude/rules/api-error-handling.md`
- **커밋·브랜치·PR** → `.claude/rules/git-conventions.md`

## 도메인 참조 문서

API 설계·DB·이벤트 관련 작업 시 아래 문서를 먼저 확인한다.

- **API 설계 (Swagger/OpenAPI)** → `.claude/docs/api-design.md`
- **DB 테이블 구조** → `.claude/docs/db-schema.md`
- **Kafka 이벤트 계약** → `.claude/docs/events.md`

모노레포 전체(다른 서비스와의 연결) 맥락이 필요한 작업 시:

- **시스템 전체 구조 (서비스·포트·gateway 라우팅·인증 헤더 흐름)** → `../docs/architecture/overview.md`
- **서비스 간 Kafka 이벤트 흐름 (발행/소비 매트릭스·시나리오)** → `../docs/architecture/event-flow.md`

## 테스트 정책

- 새 기능은 **테스트와 함께** 추가(가능하면 TDD: 실패 테스트 → 구현 → 통과).
- 통합/영속성 테스트는 **Testcontainers(PostgreSQL)**, Kafka는 **Testcontainers(`testcontainers-kafka`, `confluentinc/cp-kafka:7.6.1`)**. H2 등 인메모리 DB로 대체하지 않는다.
- EmbeddedKafka(`spring-kafka-test`)는 macOS KRaft 브로커 충돌(`Exit.halt(1, null)`) 문제로 사용하지 않는다.
- 단언은 **AssertJ**(`assertThat`).
- 영속성 테스트는 엔티티 `create(...)` 팩토리로 객체를 만들어 라운드트립 + 감사 필드 검증(기존 `PaymentJpaRepositoryTest` 패턴).
- 통합 테스트(`*IntegrationTest`)는 루트 패키지(`com.prompthub.paymentservice`)에 위치한다(하위 패키지 아님).

## AI 작업 원칙

- 수정 가능 범위: `payment-service/`(전체)와 `../docs/`(payment-service 변경 반영 목적)에 한정한다.
  다른 서비스 소스 코드(`common-module/`, `order-service/` 등)는 수정하지 않는다. 변경이 필요한 경우 의도를 먼저 공유한다.
- 사용자 동의 없이 기존 코드/파일을 삭제·변경하지 않는다(테스트 목적이라도). 변경 전 의도를 먼저 공유.
- 추측으로 외부 이벤트 계약(Kafka 토픽/스키마)이나 미확정 구조를 임의 구현하지 않는다. 미확정 영역은 질문.
- 다른 서비스/모듈(order-service 등)의 기존 구현 방식을 그대로 복제하지 않는다. payment-service의 아키텍처·요구사항에 맞는 대안을 먼저 검토하고, 어떤 방식을 택했는지와 이유(또는 다른 모듈과 다르게 구현한 이유)를 사용자에게 공유한 뒤 진행한다.
- 작업 후 테스트 실행 결과를 사실대로 보고(실패 시 출력 포함).
