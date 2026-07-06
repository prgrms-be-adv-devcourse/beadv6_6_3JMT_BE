# payment-service 마크다운 문서 개선 계획

CLAUDE.md 및 .claude/ 하위 문서들의 품질 평가 결과를 바탕으로 마크다운 정리 작업.

---

## 현황

| 파일 | 상태 |
|------|------|
| `HELP.md` | 자동 생성 Gradle 도움말, 가치 없음 → 삭제 대상 |
| `.claude/plans/` | 완료된 계획 01~08이 현행 디렉토리에 혼재 → 아카이빙 필요 |

---

## 작업 범위 및 순서

### 1️⃣ **HELP.md 삭제** (우선순위: HIGH)

**파일**: `payment-service/HELP.md`

**이유**:
- 자동 생성된 Gradle 도움말 (프로젝트 특화 내용 없음)
- CLAUDE.md가 이미 모든 운영 정보 포함

**영향 범위**: 최소 (다른 파일에서 참조하지 않음)

---

### 2️⃣ **plans 디렉토리 정리** (우선순위: MEDIUM)

**작업 내용**: 완료된 계획 12개를 `archive/`로 이동

```bash
mkdir -p .claude/plans/archive
mv 01-payment-confirm-api.md archive/
mv 01-payment-confirm-api-impl.md archive/
mv 02-refund-api.md archive/
mv 02-refund-api-impl.md archive/
mv 03-msa-infra-setup.md archive/
mv 04-buyer-role-check.md archive/
mv 05-routing-test-guide.md archive/
mv 06-local-api-test-plan.md archive/
mv 06-local-api-test-plan-troubleshooting.md archive/
mv 07-test-performance-improvement.md archive/
mv 07-test-performance-improvement-impl.md archive/
mv 08-code-review-2026-06-29.md archive/
```

**이유**: 진행 중인 계획과 완료된 계획 분리. git 히스토리는 보존됨.

---

### 3️⃣ **CLAUDE.md 개선** (우선순위: HIGH)

**파일**: `payment-service/CLAUDE.md`

코드베이스 분석으로 발견한 누락·오류 3가지.

#### 수정 1 — `docker-compose` 주석 정정

현재:
```bash
docker-compose up -d                   # 로컬 PostgreSQL (호스트 5433)
```
변경:
```bash
docker-compose up -d                   # 로컬 PostgreSQL (호스트 5433) + Kafka (포트 9092)
```

**이유**: `docker-compose.yml`에 `payment-kafka-dev`(apache/kafka:3.7.0, 9092)가 포함되어 있는데 PostgreSQL만 언급되어 있음.

#### 수정 2 — `.env` 항목에 Toss env var 추가

현재:
```
- 실행 전 `.env` 필요: `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` (gitignore 대상, 커밋 금지).
```
변경:
```
- 실행 전 `.env` 필요: `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `TOSS_SECRET_KEY` / `TOSS_TEST_MODE` (gitignore 대상, 커밋 금지).
  - `TOSS_SECRET_KEY` 미설정 시 더미 키(`test-dummy-key`)로 기동되어 실제 PG 연동 불가.
  - `TOSS_TEST_MODE=true` 설정 시 Toss 테스트 모드 활성화 (기본값 `false`).
```

**이유**: `application.yaml`에 `${TOSS_SECRET_KEY:test-dummy-key}`, `${TOSS_TEST_MODE:false}` 기본값이 있어 기동은 되지만, 실제 결제 테스트 시 반드시 설정 필요. 비자명한 함정.

#### 수정 3 — Config Server / Eureka 단독 기동 가능 명시

현재 해당 내용 없음. 추가:
```
- Config Server(`http://localhost:8888`) 및 Eureka(`http://localhost:8761`)는 `optional` 설정으로 없어도 기동된다. 단, 서비스 메시 기능(설정 중앙화, 서비스 디스커버리)은 비활성화 상태가 된다.
```

**이유**: `application.yaml`에 `optional:configserver` 및 Eureka `enabled: true`이지만 optional이라 단독 실행 가능. 신규 기여자가 Config Server/Eureka 없이 기동 시 오류를 예상해 헤맬 수 있음.

#### 수정 4 — 중복 빈 줄 제거 (minor)

현재 52~53번째 줄에 빈 줄 2개 → 1개로 정정.

---

## 작업 체크리스트

- [x] HELP.md 삭제 (git 미추적 파일, rm으로 제거)
- [x] plans 아카이빙 (12개 파일 이동) — commit: 6a80d4c
- [x] CLAUDE.md 수정 1: docker-compose 주석 정정 — commit: 0c36a7d
- [x] CLAUDE.md 수정 2: .env 항목에 Toss env var 추가 — commit: 0c36a7d
- [x] CLAUDE.md 수정 3: Config Server / Eureka 단독 기동 설명 추가 — commit: 0c36a7d
- [x] CLAUDE.md 수정 4: 중복 빈 줄 제거 — commit: 0c36a7d

---

## 주의사항

- 코드 변경 없음 (문서 정리 전용)
- plans 아카이빙은 git 히스토리가 보존되므로 안전함
