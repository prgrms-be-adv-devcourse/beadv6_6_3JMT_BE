# order-service AGENTS.md 개편 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실제 프로젝트 설정과 구현 관례에 맞는 한글 `order-service` 전용 AGENTS.md를 작성한다.

**Architecture:** 기존 저장소 전체 영문 초안을 모듈 전용 혼합형 지침으로 교체한다. 현재 구현을 사실대로 설명하고, 새로운 변경에 적용할 계층·데이터·메시징·보안·테스트 규칙과 작업 체크리스트를 한 문서에 제공한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Cloud 2025.1.2, Gradle Groovy DSL, JPA, QueryDSL, PostgreSQL, Kafka, Redis, REST/OpenFeign, gRPC, Resilience4j, springdoc-openapi

## Global Constraints

- 적용 범위는 `order-service/**`이며 저장소 공통 규칙은 이 모듈 작업에 필요한 내용만 포함한다.
- 설명과 규칙은 한글로 작성하고 코드 식별자·경로·명령어·기술명은 원문을 유지한다.
- `domain/model`이 JPA Entity를 포함하는 현재 구조를 부정하지 않되 새 Web·Kafka·Redis·gRPC·Feign 의존성을 domain에 추가하지 않는다.
- 애플리케이션 코드, Gradle 설정, 런타임 설정은 변경하지 않는다.
- 기존 작업 트리의 사용자 변경과 삭제 파일은 수정하거나 커밋하지 않는다.
- `AGENTS.md`, 이 설계 문서, 이 구현 계획만 stage하고 commit한다.
- 작업 브랜치는 `feature/#329-codex-setting`, 관련 이슈는 `#329`, PR 대상 브랜치는 `develop`이다.

---

### Task 1: AGENTS.md를 order-service 전용 지침으로 교체

**Files:**
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: `docs/superpowers/specs/2026-07-14-order-service-agents-guide-design.md`, 루트 `settings.gradle`, 루트 `build.gradle`, `build.gradle`, `src/main/resources/application.yml`, `src/test/resources/application-test.yml`
- Produces: `order-service/**` 작업에서 에이전트가 따라야 하는 단일 지침 문서 `AGENTS.md`

- [ ] **Step 1: 문서의 목적과 적용 범위를 작성한다**

  `AGENTS.md` 첫 부분에 다음 내용을 명시한다.

  - 문서는 `order-service`와 하위 경로에 적용된다.
  - 사용자 요청, 상위 지침, 더 가까운 하위 `AGENTS.md` 순으로 충돌을 해석한다.
  - 작업 전 실제 코드와 설정을 확인하고 현재 스타일을 보존한다.

- [ ] **Step 2: 실제 기술 기준과 빌드 명령을 작성한다**

  다음 값을 그대로 반영한다.

  - Java 21
  - Spring Boot 4.1.0
  - Spring Cloud 2025.1.2
  - Gradle Groovy DSL 멀티 프로젝트
  - 모듈 디렉터리 실행 명령 `../gradlew :order-service:test`, `../gradlew :order-service:build`
  - 루트 디렉터리 실행 명령 `./gradlew :order-service:test`, `./gradlew :order-service:build`

- [ ] **Step 3: 서비스 책임과 서비스 간 경계를 작성한다**

  주문, 장바구니, 구매/결제 내역, 콘텐츠 접근·다운로드 상태, 주문 만료, 결제 이벤트 반영, 관리자 주문 조회를 책임으로 정의한다. 상품·판매자 정보는 포트를 통한 REST/gRPC 조회, 결제 상태는 Kafka 이벤트 연동, Toss Payments 직접 호출 금지, 타 서비스 내부 클래스·DB 스키마·Redis Key 직접 접근 금지를 명시한다.

- [ ] **Step 4: 실제 패키지와 의존성 규칙을 작성한다**

  `presentation`, `application/usecase`, `application/service`, `application/client`, `domain/model`, `domain/repository`, `infra`, `global`의 역할을 기술한다. Controller는 Use Case를 호출하고, 외부 연동은 `application/client` 포트와 `infra` 어댑터로 분리하며, Repository 포트와 Spring Data 구현을 분리하는 현재 관례를 유지하도록 한다.

  현재 예외도 명시한다.

  - `domain/model`은 JPA annotation을 사용한다.
  - 기존 `application`의 presentation DTO 또는 infra payload 참조는 관련 작업 없이 일괄 수정하지 않는다.
  - 새 코드에서는 계층 역방향 의존성을 늘리지 않는다.

- [ ] **Step 5: API·보안·데이터 규칙을 작성한다**

  구매자 경로 `/api/v1/orders/**`, `/api/v1/cart/**`는 `X-User-Id`와 `X-User-Role=BUYER`, 관리자 경로 `/api/v1/admin/**`는 `X-User-Role=ADMIN`을 사용하는 현재 Interceptor 구조를 기술한다. JWT 파싱·비밀키 추가 금지, DTO/Swagger 동시 변경, Entity 직접 응답 금지, QueryDSL projection/custom repository 관례, 트랜잭션 최소화와 `readOnly = true` 조회 규칙을 포함한다.

- [ ] **Step 6: 프로파일별 외부 연동 규칙을 작성한다**

  다음 매핑을 정확히 작성한다.

  - `default/local`: `ProductRestClientAdapter`, `SellerRestFallbackClient`
  - `dev/prod`: `ProductGrpcClientAdapter`, `SellerGrpcClientAdapter`
  - gRPC 계약 위치: `../grpc/order`, `../grpc/product`, `../grpc/user`
  - 상품 gRPC 장애 처리: deadline, CircuitBreaker, Bulkhead, 상태 코드 매핑

- [ ] **Step 7: Kafka·Outbox·Redis 규칙을 작성한다**

  Kafka 수동 ACK, 1초 간격 3회 재시도, `.DLT`, 명시적 payload, `eventId + consumerGroup` 멱등성, 주문 변경과 이벤트 발행의 Outbox 우선 원칙을 포함한다. Redis 주문 만료 처리에는 예약, Worker, 재시도, DLQ와 Scheduler의 다중 인스턴스 중복 실행 검토가 필요함을 명시한다.

- [ ] **Step 8: 테스트와 에이전트 작업 절차를 작성한다**

  Mockito 단위 테스트, `@DataJpaTest`, `@SpringBootTest`, Embedded Kafka의 선택 기준을 작성한다. 작업 전 `git status`, 관련 코드·테스트·설정 확인, 변경 후 범위별 테스트·diff 검토·비밀정보 확인 절차와 금지사항을 체크리스트로 제공한다.

### Task 2: 문서 내용과 형식을 검증

**Files:**
- Verify: `AGENTS.md`
- Verify: `docs/superpowers/specs/2026-07-14-order-service-agents-guide-design.md`

**Interfaces:**
- Consumes: Task 1에서 작성한 `AGENTS.md`
- Produces: 형식 오류와 핵심 사실 누락이 없는 검증 결과

- [ ] **Step 1: Markdown 공백 오류를 검사한다**

  Run:

  ```bash
  git diff --check --no-index /dev/null AGENTS.md
  ```

  Expected: exit code 1은 새 파일 차이 때문에 허용하되 `trailing whitespace`, `space before tab`, `new blank line at EOF` 오류가 없어야 한다.

- [ ] **Step 2: 필수 프로젝트 사실을 검사한다**

  Run:

  ```bash
  rg -n "Java 21|Spring Boot 4\.1\.0|Spring Cloud 2025\.1\.2|\.\./gradlew :order-service:test|default/local|dev/prod|eventId.*consumerGroup|X-User-Id|X-User-Role" AGENTS.md
  ```

  Expected: 모든 패턴이 한 번 이상 검색된다.

- [ ] **Step 3: 적용 범위와 계층 경계를 검사한다**

  Run:

  ```bash
  rg -n "order-service/\*\*|presentation|application/usecase|application/client|domain/model|domain/repository|infra|global" AGENTS.md
  ```

  Expected: 적용 범위와 모든 주요 패키지가 검색된다.

- [ ] **Step 4: 기존 영문 초안이 남지 않았는지 검사한다**

  Run:

  ```bash
  rg -n "Project Overview|Repository Structure|Technology Baseline|Service Responsibilities|Prohibited Actions" AGENTS.md
  ```

  Expected: exit code 1이며 출력이 없다.

- [ ] **Step 5: 변경 범위를 확인한다**

  Run:

  ```bash
  git status --short -- AGENTS.md docs/superpowers/specs/2026-07-14-order-service-agents-guide-design.md docs/superpowers/plans/2026-07-14-order-service-agents-guide.md
  ```

  Expected: 위 세 문서만 새 파일 또는 수정 파일로 표시되며 stage되지 않는다.

- [ ] **Step 6: 최종 diff를 검토한다**

  Run:

  ```bash
  git diff --no-index /dev/null AGENTS.md
  ```

  Expected: 한글 중심의 `order-service` 전용 지침만 포함되고 애플리케이션 코드 변경은 없다.

### Task 3: 문서 변경을 커밋하고 PR 생성

**Files:**
- Commit: `AGENTS.md`
- Commit: `docs/superpowers/specs/2026-07-14-order-service-agents-guide-design.md`
- Commit: `docs/superpowers/plans/2026-07-14-order-service-agents-guide.md`
- Read only: `../.github/PULL_REQUEST_TEMPLATE.md`

**Interfaces:**
- Consumes: Task 2에서 검증한 세 문서, 이슈 `#329`, 기존 PR 템플릿
- Produces: `feature/#329-codex-setting` 브랜치의 문서 커밋과 `develop` 대상 GitHub PR

- [ ] **Step 1: 브랜치와 stage 대상 파일을 확인한다**

  Run:

  ```bash
  git branch --show-current
  git status --short
  ```

  Expected: 현재 브랜치는 `feature/#329-codex-setting`이며 사용자 소유 변경과 세 문서가 구분되어 표시된다.

- [ ] **Step 2: order-service 전체 테스트를 실행한다**

  Run:

  ```bash
  ../gradlew :order-service:test
  ```

  Expected: `BUILD SUCCESSFUL`이며 실패 테스트가 없다.

- [ ] **Step 3: 요청 범위의 세 문서만 stage한다**

  Run:

  ```bash
  git add AGENTS.md docs/superpowers/specs/2026-07-14-order-service-agents-guide-design.md docs/superpowers/plans/2026-07-14-order-service-agents-guide.md
  git diff --cached --name-only
  ```

  Expected: 위 세 문서만 출력되고 `.gitignore`와 루트 문서 삭제는 포함되지 않는다.

- [ ] **Step 4: 커밋 컨벤션에 맞는 한글 커밋을 생성한다**

  Run:

  ```bash
  git commit -m "docs: order-service 에이전트 작업 지침 추가"
  ```

  Expected: 커밋이 성공하고 제목에 소괄호가 없으며 허용 타입 `docs`를 사용한다.

- [ ] **Step 5: 원격 브랜치로 push한다**

  Run:

  ```bash
  git push -u origin 'feature/#329-codex-setting'
  ```

  Expected: 원격 추적 브랜치가 설정되고 push가 성공한다.

- [ ] **Step 6: 기존 PR 템플릿으로 PR을 생성한다**

  PR 제목은 `[DOCS] order-service - 에이전트 작업 지침 추가`, base는 `develop`, head는 `feature/#329-codex-setting`으로 한다. 본문에는 다음 내용을 포함한다.

  - 실제 프로젝트 설정을 반영한 한글 order-service 전용 지침
  - 설계·구현 계획 문서
  - 실행 명령 `./gradlew :order-service:test`와 성공 결과
  - `Closed #329`
  - 애플리케이션 코드, API, DB, Kafka, gRPC 계약 변경 없음
  - 기존 `.github/PULL_REQUEST_TEMPLATE.md`의 체크리스트

  Expected: 원격 저장소에 열린 PR이 생성되고 URL을 확인할 수 있다.
