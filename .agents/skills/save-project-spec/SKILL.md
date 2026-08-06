---
name: save-project-spec
description: Superpowers의 brainstorming·writing-plans·subagent-driven-development를 포함해 저장소 작업의 기획·스펙 문서와 구현 계획 산출물을 관리한다. 새 기능·버그 수정·리팩터링의 승인된 스펙을 저장하거나, "기획 문서 저장", "스펙 문서 작성", "구현 계획 작성", "plan 저장"을 요청받았을 때 사용한다. 스펙만 `docs/records/plan/`의 도메인별 하위 디렉터리에 추적하고 별도 구현 계획 문서는 만들거나 보관하지 않는다.
---

# 프로젝트 스펙 저장

Superpowers의 기본 문서 경로를 이 저장소 정책으로 재정의한다. 승인된 기획·스펙만 도메인별 기록으로 남기고, 구현 순서와 태스크는 Codex의 작업 계획에서 관리한다.

## 산출물 계약

| 단계 | 처리 |
| --- | --- |
| `superpowers:brainstorming` | 승인된 스펙을 `docs/records/plan/<domain>/`에 저장한다. |
| `superpowers:writing-plans` | `update_plan` 등 현재 작업의 계획 기능으로만 관리한다. Markdown 계획 문서를 만들지 않는다. |
| `superpowers:subagent-driven-development`·`executing-plans` | 저장된 스펙과 현재 Codex 작업 계획을 사용하고 체크리스트·태스크 문서를 새로 만들지 않는다. |

- `docs/superpowers/specs/`와 `docs/superpowers/plans/`에 새 문서를 만들지 않는다.
- `*-tasks.md`, `*-plan.md`, `*-implementation.md` 같은 별도 구현 계획 산출물을 만들지 않는다.
- 이 계약을 Superpowers 기본 저장 절차보다 우선하는 사용자·프로젝트 경로 재정의로 적용한다.
- 기존 문서를 자동으로 이동하거나 삭제하지 않는다. 사용자가 명시적으로 요청한 경우에만 별도 작업으로 다룬다.

## 도메인 결정

1. 사용자 요청, 대상 파일과 적용되는 `AGENTS.md`에서 주 도메인을 확인한다.
2. `<domain>-service/**` 작업은 같은 이름의 `docs/records/plan/<domain>/`으로 매핑한다.

| 대상 경로 | 스펙 저장 경로 |
| --- | --- |
| `admin-service/**` | `docs/records/plan/admin/` |
| `order-service/**` | `docs/records/plan/order/` |
| `payment-service/**` | `docs/records/plan/payment/` |
| `product-service/**` | `docs/records/plan/product/` |
| `settlement-service/**` | `docs/records/plan/settlement/` |
| `user-service/**` | `docs/records/plan/user/` |
| 인프라·배포·여러 도메인의 횡단 관심사 | `docs/records/plan/infra/` |

3. 여러 서비스가 연관돼도 하나의 도메인이 결정을 소유하면 그 도메인에 하나만 저장한다.
4. 서로 독립적인 도메인 결정이 섞였으면 스펙을 도메인별로 분리한다.
5. 주 도메인이 불명확하거나 대상 도메인 디렉터리가 없으면 임의로 새 디렉터리를 만들지 말고 저장 전에 사용자에게 확인한다.

## 파일명 결정

1. 현재 브랜치가 `<type>/#<issue>-<slug>`이면 `<issue>-<slug>.md`를 사용한다.
2. 브랜치에 이슈 번호가 없지만 사용자가 이슈를 지정했으면 그 번호와 영어 소문자 kebab-case slug를 사용한다.
3. 이슈 번호가 없는 허용된 초기 설정 작업이면 `YYYY-MM-DD-<slug>.md`를 사용한다.
4. 같은 경로에 파일이 이미 있으면 덮어쓰지 않는다. 같은 작업의 갱신인지 새 문서인지 확인한다.

예시:

- `feat/#15-partial-refund` → `docs/records/plan/payment/15-partial-refund.md`
- 이슈 없는 공통 CI 정리 → `docs/records/plan/infra/2026-08-06-ci-cleanup.md`

## 저장 절차

1. 저장소 루트와 대상 경로의 `AGENTS.md`, 필요한 공통 규칙을 먼저 읽는다.
2. `git branch --show-current`와 작업 범위를 확인해 도메인, 이슈 번호와 slug를 정한다.
3. 승인된 brainstorming 결과만 스펙에 반영한다. 승인되지 않은 선택지나 구현 중 생긴 임시 태스크를 확정 사항처럼 기록하지 않는다.
4. 최소한 목적과 배경, 확정된 결정과 근거, 범위, 검증 기준을 포함한다. 섹션은 작업 성격에 맞게 조정한다.
5. 문서를 쓴 뒤 `TBD`, `TODO`, 상충하는 결정, 모호한 요구사항과 잘못된 경로를 자체 검토한다.
6. 사용자에게 저장 경로를 알리고 검토를 요청한다. 커밋은 사용자가 별도로 요청했을 때만 수행한다.

## 중단 조건

- 도메인 소유권이 둘 이상으로 해석되고 주 도메인을 정할 근거가 없다.
- 이슈 번호 또는 slug 후보가 기존 문서와 충돌하지만 동일 작업인지 판단할 수 없다.
- 사용자가 별도 구현 계획 문서 저장을 요청해 이 저장소 정책과 충돌한다.

위 조건에서는 파일을 추측해 만들지 말고 선택지와 영향을 간단히 설명한 뒤 확인한다.
