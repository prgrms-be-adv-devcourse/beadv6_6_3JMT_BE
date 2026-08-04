# PromptHub BE — Codex 루트 지침

> Codex가 이 저장소에서 작업할 때 적용하는 전역 라우팅 문서다.
> 세부 기술 규칙은 실제 작업 경로에 가장 가까운 `AGENTS.md`에서 추가한다.

## 적용 우선순위

- 사용자의 현재 요청과 시스템·보안 지침을 우선한다.
- 이 문서는 저장소 전체의 기본 규칙으로 적용한다.
- 대상 경로에 더 가까운 `AGENTS.md`가 있으면 그 경로의 추가 규칙을 함께 적용한다.
- 규칙이 충돌하거나 작업 범위가 불명확하면 임의로 넓히지 말고 사용자에게 확인한다.

## 전역 안전 규칙

- 저장소 밖의 개인 설정·메모리 파일을 생성하거나 수정하지 않는다.
- `.env`, 개인 키, 인증서, 토큰 등 비밀정보의 값을 출력하거나 코드·문서에 복사하지 않는다.
- 기존 사용자 변경과 무관한 파일을 수정하거나 되돌리거나 stage하지 않는다.
- 삭제, 대규모 변경, 외부 시스템 쓰기는 요청 범위와 권한을 먼저 확인한다.
- 특정 작업자의 경로 제한이나 역할을 공유 지침에서 추론하지 않는다. 현재 요청과 경로 규칙으로 작업 범위를 정한다.

## 작업 범위와 지침 로드

작업을 시작할 때 대상 경로를 먼저 식별하고 저장소 루트부터 해당 경로까지 적용되는 `AGENTS.md`를 완전히 읽는다. Codex가 루트에서 시작해 하위 문서를 자동으로 읽지 못하는 경우에도 다음 표에 맞춰 명시적으로 로드한다.

| 대상 경로 | 추가로 읽을 지침 |
| --- | --- |
| `settlement-service/**` | `settlement-service/AGENTS.md` |
| `user-service/**` | `user-service/AGENTS.md` |
| `order-service/**` | `order-service/AGENTS.md` |
| `admin-service/src/main/java/com/prompthub/admin/settlement/**` | `admin-service/src/main/java/com/prompthub/admin/settlement/AGENTS.md` |
| `admin-service/src/test/java/com/prompthub/admin/settlement/**` | `admin-service/src/test/java/com/prompthub/admin/settlement/AGENTS.md`와 대응 main 경로의 `AGENTS.md` |
| 그 밖의 경로 | 더 가까운 `AGENTS.md`가 있으면 적용하고, 없으면 이 문서만 적용 |

### 단일 서비스 작업

- 대상 서비스의 코드와 문서만 읽는다.
- 다른 서비스의 내부 코드나 서비스 전용 지침은 열지 않는다.
- 대상 서비스가 직접 사용하는 `grpc/`, `common-module/`, `docs/`의 공유 계약은 필요한 범위에서만 확인한다.

### 다중 서비스 작업

- 사용자가 명시했거나 변경에 직접 필요한 서비스만 범위에 포함한다.
- 실제 대상 경로마다 적용되는 `AGENTS.md`를 모두 읽고 함께 적용한다.
- 서비스 간 계약은 관련 `grpc/`, `common-module/`, `docs/api-spec/`, 이벤트 문서를 기준으로 확인한다.
- 영향 가능성만으로 관련 없는 서비스 전체를 탐색하지 않는다.

### 루트 및 공유 영역 작업

- 루트 빌드, 인프라, `docs/`, `scripts/`, 공용 설정 작업에서는 서비스별 지침을 읽지 않는다.
- `build.gradle`, `settings.gradle`, `gradle/`, `style/`, `docker/`, `k8s/`, `grpc/`, `common-module/`, `docs/`, `scripts/`는 서비스 경계 밖의 공유 자원이다.
- 공유 자원도 현재 작업에 필요한 파일만 읽는다.

## 공용 Codex 스킬 라우팅

저장소 공용 스킬은 `.agents/skills/`에 있다. 아래 조건에 해당하면 해당 `SKILL.md`를 완전히 읽고 따른다.

| 요청 또는 작업 | 사용할 스킬 |
| --- | --- |
| 요청된 변경을 커밋 | `commit-project-changes` |
| 이슈나 작업 설명으로 브랜치 생성 | `create-project-branch` |
| GitHub 이슈 생성 | `create-project-issue` |
| PR 생성 또는 기존 PR 갱신 | `create-project-pr` |
| 커밋·PR 전 전체 diff 검증 또는 코드 리뷰 | `verify-project-changes` |

- 실제 절차의 단일 진실 공급원은 `.agents/skills/<스킬 이름>/SKILL.md`다. 이 문서에 스킬 본문을 복사하지 않는다.
- 모듈 전용 스킬은 해당 모듈의 `AGENTS.md`에서 별도로 라우팅한다.
- 스킬 적용 범위나 대상 변경 집합이 애매하면 실행 전에 사용자에게 질문한다.

## 시스템 개요

```text
클라이언트
    │ Authorization: Bearer {accessToken}
    ▼
API Gateway (`apigateway/`, 포트 8000)
    │ JWT 검증, X-User-Id·X-User-Role 헤더 주입
    ▼
각 도메인 서비스
    ├─ Config Server (`config/`, 포트 8888)에서 설정 조회
    ├─ Discovery (`discovery/`, 포트 8761)에 등록
    └─ Kafka 또는 gRPC로 필요한 서비스와 통신
```

로컬 기동이 필요한 작업에서는 일반적으로 Config Server → Discovery → API Gateway → 대상 서비스 순서를 따른다. 실제 실행 전에는 대상 서비스 설정과 프로파일을 확인한다.

## 빌드와 검증

- 이 저장소는 Gradle 멀티모듈 프로젝트이며 Java 21을 사용한다.
- 서비스 코드 변경 후에는 먼저 해당 모듈 테스트를 실행한다. 예: `./gradlew :payment-service:test`.
- 컴파일만 먼저 확인할 필요가 있으면 해당 모듈의 `compileJava` task를 사용한다.
- `common-module/` 또는 루트 빌드 설정을 변경하면 직접 영향을 받는 소비 모듈까지 테스트한다.
- 여러 모듈에 걸친 변경은 각 대상 모듈 테스트를 실행하고, 필요하면 `./gradlew test`로 전체 회귀를 확인한다.
- 문서·지침·스킬만 변경한 경우에는 경로·내용·스킬 구조를 검토하고 skill validation과 `git diff --check`를 수행한다.
- 실행하지 못한 검증은 성공한 것처럼 표현하지 말고 이유와 남은 검증을 명시한다.

## 공유 문서 위치

| 문서 | 경로 |
| --- | --- |
| 아키텍처 개요 | `docs/architecture/overview.md` |
| Spring Cloud 구조 | `docs/architecture/spring-cloud.md` |
| Kafka 이벤트 흐름 | `docs/architecture/event-flow.md` |
| ERD 전체 | `docs/erd/overview.md` |
| 스키마 레퍼런스 | `docs/erd/schema.md` |
| 도메인 용어 사전 | `docs/domain-glossary/` |
| API 명세 | `docs/api-spec/` |
| 에러 코드 | `docs/error-codes.md` |

Git에서 의도적으로 제외된 로컬 문서는 추적 대상에 나타나지 않아도 ignore 규칙을 임의로 변경하거나 되돌리지 않는다.
