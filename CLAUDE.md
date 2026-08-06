# PromptHub BE — 루트 CLAUDE.md

> 라우팅 문서. 실제 작업 규칙과 도메인 컨텍스트는 각 서비스 폴더의 CLAUDE.md를 참조한다.
> 특정 개인의 작업 메모가 아니라, 이 저장소에서 작업하는 모든 세션이 따르는 전역 설정이다.

---

## ⚠️ 전역 규칙 (예외 없이 적용)

- **전역 `.claude` 설정을 건드리지 않는다.** 사용자 홈(`~/.claude/`)의 개인 설정·메모리 파일은
이 저장소 밖의 개인 자산이다. 이 프로젝트에서 작업하며 그 파일을 새로 만들거나 고치지 않는다.
- **단일 서비스를 작업할 때는 다른 서비스의 코드·룰·CLAUDE.md를 읽지 않는다.** 예를 들어
`payment-service/`를 작업 중이면 `order-service/src/**`, `order-service/CLAUDE.md`,
`order-service/.claude/rules/**`를 열어보지 않는다. 루트 `.claude/rules/`·`docs/`·`grpc/`·
`common-module/`처럼 **여러 서비스가 함께 쓰도록 둔 공유 자원**은 이 제약의 예외다 — 특정
서비스 소유가 아니라 원래부터 서비스 경계 밖에 있는 자산이라 자유롭게 참조한다.

---

## 📋 CLAUDE.md 로드 규칙

**⚠️ 토큰 절약: 전역 설정 작업 시 서비스 폴더의 CLAUDE.md를 로드하지 마십시오.**

### 각 서비스 CLAUDE.md를 로드하는 경우

- **user-service/**, **payment-service/**, **product-service/**, **settlement-service/** 작업 → 각 폴더의 `CLAUDE.md` 로드 필수
- **admin-service/**, **order-service/** 작업 → 아직 자체 `CLAUDE.md` 없음. 있으면 로드, 없으면 루트 문서 기준으로 진행
- 해당 서비스의 도메인 모델, 아키텍처, 예외 처리, 컨벤션 등이 필요한 경우만

### 각 서비스 CLAUDE.md를 로드하지 않는 경우

- **apigateway/** 작업 (라우팅, 필터, 인증)
- **config/** 작업 (설정 서버, 프로파일)
- **discovery/** 작업 (Eureka, 레지스트리)
- **docker-compose.yml**, **Dockerfile** 등 전역 인프라 작업
- **scripts/**, **docs/**, 루트 수준 설정 작업
- **pom.xml** (parent) 또는 루트 빌드 파일 수정

### 판단 기준


| 폴더                          | 서비스 CLAUDE.md 필요 여부     |
| --------------------------- | ----------------------- |
| `user-service/src/**`       | ✅ 필수                    |
| `admin-service/src/**`      | ⚠️ CLAUDE.md 없음(있으면 로드) |
| `order-service/src/**`      | ⚠️ CLAUDE.md 없음(있으면 로드) |
| `payment-service/src/**`    | ✅ 필수                    |
| `product-service/src/**`    | ✅ 필수                    |
| `settlement-service/src/**` | ✅ 필수                    |
| `apigateway/src/**`         | ❌ 불필요                   |
| `config/src/**`             | ❌ 불필요                   |
| `discovery/src/**`          | ❌ 불필요                   |
| `docker-compose.yml`        | ❌ 불필요                   |
| `scripts/`                  | ❌ 불필요                   |
| `docs/`                     | ❌ 불필요                   |


---

## 담당 서비스


| 서비스                | 폴더                    | 상태   | 세부 규칙                          |
| ------------------ | --------------------- | ---- | ------------------------------ |
| User Service       | `user-service/`       | 구현 중 | `user-service/CLAUDE.md`       |
| Admin Service      | `admin-service/`      | 구현 중 | 없음                             |
| Order Service      | `order-service/`      | 구현 중 | 없음                             |
| Payment Service    | `payment-service/`    | 구현 중 | `payment-service/CLAUDE.md`    |
| Product Service    | `product-service/`    | 구현 중 | `product-service/CLAUDE.md`    |
| Settlement Service | `settlement-service/` | 구현 중 | `settlement-service/CLAUDE.md` |
| API Gateway        | `apigateway/`         | 구현 중 | `apigateway/CLAUDE.md`         |
| Config Server      | `config/`             | 구현 중 | `config/CLAUDE.md`             |
| Discovery (Eureka) | `discovery/`          | 구현 중 | `discovery/CLAUDE.md`          |


---

## 시스템 전체 흐름

```
클라이언트
    │  Authorization: Bearer {accessToken}
    ▼
API Gateway (apigateway/) — 포트 8000
    │  JWT 검증 → X-User-Id, X-User-Role 헤더 주입
    ▼
각 서비스 (user-service 등)
    │  Config Server에서 설정 읽어옴
    ▼
Config Server (config/) — 포트 8888
Discovery (discovery/) — 포트 8761
```

**기동 순서**: Config Server → Discovery → API Gateway → User Service

---

## 설계 문서 위치


| 문서                                    | 경로                                            |
| ------------------------------------- | --------------------------------------------- |
| 아키텍처 개요                               | `docs/architecture/overview.md`               |
| Spring Cloud 구조                       | `docs/architecture/spring-cloud.md`           |
| 설정 관리 (프로파일·configs/ 중앙화 — 규칙·마이그레이션) | `docs/records/plan/infra/adr-config-management.md` |
| 설정 중앙화 결정 배경 (ADR)                    | `docs/records/plan/infra/adr-0004-centralized-config.md` |
| 배포·브랜치 전략 (ADR)                       | `docs/records/plan/infra/adr-0005-develop-deploy-main-freeze(v).md` |
| 이벤트 흐름 (Kafka)                        | `docs/architecture/event-flow.md`             |
| ERD 전체                                | `docs/erd/overview.md`                        |
| 스키마 레퍼런스                              | `docs/erd/schema.md`                          |
| 도메인 용어 사전                             | `docs/domain-glossary/`                       |
| API 명세                                | `docs/api-spec/`                              |
| 에러 코드                                 | `docs/error-codes.md`                         |

> 과거 아키텍처 결정 기록(ADR)은 `docs/adr/`(로컬 전용, git 미추적)에 모여 있었으나 전부
> `docs/records/plan/{도메인}/adr-*.md`로 이동해 git에 커밋된다. 도메인별로 흩어져 있으니
> (예: gateway 라우팅 ADR은 `docs/records/plan/gateway/`, admin 아키텍처 ADR은
> `docs/records/plan/admin/`) 특정 ADR을 찾을 때는 위 표의 대표 문서 몇 개 외에는 해당
> 도메인 폴더를 함께 훑는다.

