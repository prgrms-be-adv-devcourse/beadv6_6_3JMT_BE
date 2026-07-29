# PromptHub BE — 루트 CLAUDE.md

> 라우팅 문서. 실제 작업 규칙과 도메인 컨텍스트는 각 서비스 폴더의 CLAUDE.md를 참조한다.

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
| 폴더 | 서비스 CLAUDE.md 필요 여부 |
|------|-------------------------|
| `user-service/src/**` | ✅ 필수 |
| `admin-service/src/**` | ⚠️ CLAUDE.md 없음(있으면 로드) |
| `order-service/src/**` | ⚠️ CLAUDE.md 없음(있으면 로드) |
| `payment-service/src/**` | ✅ 필수 |
| `product-service/src/**` | ✅ 필수 |
| `settlement-service/src/**` | ✅ 필수 |
| `apigateway/src/**` | ❌ 불필요 |
| `config/src/**` | ❌ 불필요 |
| `discovery/src/**` | ❌ 불필요 |
| `docker-compose.yml` | ❌ 불필요 |
| `scripts/` | ❌ 불필요 |
| `docs/` | ❌ 불필요 |

---

## 담당 서비스

| 서비스 | 폴더 | 상태 | 세부 규칙 |
|--------|------|------|----------|
| User Service | `user-service/` | 구현 중 | `user-service/CLAUDE.md` |
| Admin Service | `admin-service/` | 구현 중 | 없음 |
| Order Service | `order-service/` | 구현 중 | 없음 |
| Payment Service | `payment-service/` | 구현 중 | `payment-service/CLAUDE.md` |
| Product Service | `product-service/` | 구현 중 | `product-service/CLAUDE.md` |
| Settlement Service | `settlement-service/` | 구현 중 | `settlement-service/CLAUDE.md` |
| API Gateway | `apigateway/` | 구현 중 | `apigateway/CLAUDE.md` |
| Config Server | `config/` | 구현 중 | `config/CLAUDE.md` |
| Discovery (Eureka) | `discovery/` | 구현 중 | `discovery/CLAUDE.md` |

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

| 문서 | 경로                                  |
|------|-------------------------------------|
| 아키텍처 개요 | `docs/architecture/overview.md`     |
| Spring Cloud 구조 | `docs/architecture/spring-cloud.md` |
| 설정 관리 (프로파일·configs/ 중앙화 — 규칙·마이그레이션) | `docs/adr/config-management.md`     |
| 설정 중앙화 결정 배경 (ADR) | `docs/adr/0004-centralized-config.md` |
| 배포·브랜치 전략 (ADR) | `docs/adr/0005-develop-deploy-main-freeze.md` |
| 이벤트 흐름 (Kafka) | `docs/architecture/event-flow.md`   |
| ERD 전체 | `docs/erd/overview.md`              |
| 스키마 레퍼런스 | `docs/erd/schema.md`                |
| 도메인 용어 사전 | `docs/domain-glossary/`             |
| API 명세 | `docs/api-spec/`                    |
| 에러 코드 | `docs/error-codes.md`               |

> ⚠ `docs/adr/`는 `.gitignore`(217번 줄)에 의해 git 추적 대상에서 **의도적으로** 제외된다.
> 로컬에는 파일이 있어도 커밋·푸시에는 잡히지 않는다 — 버그가 아니니 되돌리지 말 것.
