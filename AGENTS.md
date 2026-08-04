# PromptHub BE — 루트 AGENTS.md

> Codex가 이 저장소에서 작업할 때 적용하는 전역 라우팅 문서다.
> 서비스별 도메인·아키텍처 규칙은 대상 서비스의 `CLAUDE.md`가 있을 때 필요한 범위에서만 추가로 읽는다.

## 적용 우선순위

- 사용자의 현재 요청과 시스템·보안 지침을 우선한다.
- 이 파일은 저장소 전체 기본 규칙으로 적용한다.
- 서비스별 `CLAUDE.md`는 해당 서비스 작업에만 적용하는 보조 컨텍스트다.
- 규칙이 충돌하거나 작업 범위가 불명확하면 임의로 범위를 넓히지 말고 사용자에게 확인한다.

## 전역 안전 규칙

- 사용자 홈의 `~/.claude/`, `~/.codex/` 등 저장소 밖 개인 설정·메모리 파일을 생성하거나 수정하지 않는다.
- `.env`, 개인 키, 인증서, 토큰 등 비밀정보의 값을 출력하거나 코드·문서에 복사하지 않는다.
- 기존 사용자 변경 사항과 무관한 파일을 수정하거나 되돌리지 않는다.
- 삭제, 대규모 변경, 외부 시스템 쓰기는 요청 범위와 권한을 먼저 확인한다.

## 작업 범위와 컨텍스트 로드

작업을 시작할 때 대상 경로를 먼저 식별하고 다음 규칙을 따른다.

### 단일 서비스 작업

- 대상 서비스의 코드와 문서만 읽는다.
- 대상 서비스에 `CLAUDE.md`가 있으면 작업 전에 읽고 해당 규칙을 적용한다.
- 다른 서비스의 코드, `CLAUDE.md`, 서비스 전용 `.claude/rules/**`는 읽지 않는다.
- 대상 서비스가 직접 사용하는 공용 계약이나 공용 모듈은 필요한 범위에서만 확인한다.

### 다중 서비스 작업

- 사용자가 명시했거나 변경에 직접 필요한 서비스만 범위에 포함한다.
- 범위에 포함된 서비스의 `CLAUDE.md`만 읽는다.
- 서비스 간 계약 확인은 관련된 `grpc/`, `common-module/`, `docs/api-spec/`, 이벤트 문서로 제한한다.
- 영향 가능성만으로 관련 없는 서비스 전체를 탐색하지 않는다.

### 전역 작업

- 루트 빌드, 인프라, `docs/`, `scripts/`, 공용 설정 작업에서는 서비스별 `CLAUDE.md`를 읽지 않는다.
- `build.gradle`, `settings.gradle`, `gradle/`, `style/`, `docker/`, `k8s/`, `grpc/`, `common-module/`, `docs/`, `scripts/`는 서비스 경계 밖의 공유 자원이다.
- 공유 자원도 현재 작업에 필요한 파일만 읽는다.

## 서비스 및 상세 문서 라우팅

Codex가 자동으로 적용하는 저장소 지침은 `AGENTS.md`다. 기존 `CLAUDE.md`는 아래 표에 따라 명시적으로 읽는 보조 문서로 사용한다.

| 모듈 | 역할 | 직접 작업 시 추가로 읽을 문서 |
| --- | --- | --- |
| `admin-service/` | 관리자 서비스 | 없음 |
| `ai-service/` | AI 서비스 | 없음 |
| `apigateway/` | API Gateway | `apigateway/CLAUDE.md` |
| `common-module/` | 공용 모듈 | 없음 |
| `config/` | Config Server | `config/CLAUDE.md` |
| `discovery/` | Eureka Discovery | `discovery/CLAUDE.md` |
| `notification-service/` | 알림 서비스 | 없음 |
| `order-service/` | 주문 서비스 | 없음 |
| `payment-service/` | 결제 서비스 | `payment-service/CLAUDE.md` |
| `product-service/` | 상품 서비스 | `product-service/CLAUDE.md` |
| `settlement-service/` | 정산 서비스 | `settlement-service/CLAUDE.md` |
| `user-service/` | 사용자 서비스 | `user-service/CLAUDE.md` |

상세 문서가 없는 서비스는 이 루트 문서와 해당 서비스의 코드·테스트·문서를 기준으로 작업한다.

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
- 서비스 코드 변경 후에는 우선 `./gradlew :<module>:test`를 실행한다. 예: `./gradlew :payment-service:test`.
- 컴파일만 먼저 확인할 필요가 있으면 `./gradlew :<module>:compileJava`를 사용한다.
- `common-module/` 또는 루트 빌드 설정을 변경하면 직접 영향을 받는 소비 모듈까지 테스트한다.
- 여러 모듈에 걸친 변경은 각 대상 모듈 테스트를 실행하고, 필요하면 `./gradlew test`로 전체 회귀를 확인한다.
- 문서만 변경한 경우에는 경로·링크·내용과 `git diff --check`를 검토한다.
- 실행하지 못한 검증은 성공한 것처럼 표현하지 말고, 실행하지 못한 이유와 남은 검증을 명시한다.

## 설계 문서 위치

| 문서 | 경로 |
| --- | --- |
| 아키텍처 개요 | `docs/architecture/overview.md` |
| Spring Cloud 구조 | `docs/architecture/spring-cloud.md` |
| 설정 관리 | `docs/adr/config-management.md` |
| 설정 중앙화 결정 배경 | `docs/adr/0004-centralized-config.md` |
| 배포·브랜치 전략 | `docs/adr/0005-develop-deploy-main-freeze.md` |
| Kafka 이벤트 흐름 | `docs/architecture/event-flow.md` |
| ERD 전체 | `docs/erd/overview.md` |
| 스키마 레퍼런스 | `docs/erd/schema.md` |
| 도메인 용어 사전 | `docs/domain-glossary/` |
| API 명세 | `docs/api-spec/` |
| 에러 코드 | `docs/error-codes.md` |

`docs/adr/`는 `.gitignore`에서 의도적으로 Git 추적 대상에서 제외된다. 로컬 파일이 커밋 대상에 나타나지 않아도 임의로 ignore 규칙을 변경하거나 되돌리지 않는다.
