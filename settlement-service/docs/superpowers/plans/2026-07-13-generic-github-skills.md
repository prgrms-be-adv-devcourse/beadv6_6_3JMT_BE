# Generic GitHub Skills Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR 생성, 이슈 생성, 변경 검증을 특정 서비스에 제한하지 않고 저장소 전체에서 사용할 수 있는 Codex 스킬로 만든다.

**Architecture:** 범용 동작은 `create-project-pr`, `create-project-issue`, `verify-project-changes`가 담당한다. 정산 요청을 포함한 모든 도메인 요청은 이 세 스킬로 통합한다.

**Tech Stack:** Markdown 기반 Codex skills, `agents/openai.yaml`, Git, GitHub CLI, Gradle 멀티 모듈 저장소

## Global Constraints

- 새 스킬과 설계·계획 문서는 모두 `settlement-service/` 아래에 둔다.
- 저장소 루트에 `.codex`, `AGENTS.md`, 스킬 라우팅 파일을 만들지 않는다.
- PR과 코드 검증은 base 대비 전체 diff를 대상으로 한다.
- 프로젝트 규칙은 변경 경로별로 발견하고 적용하며, 규칙이 없는 모듈도 일반 코드 검토에서 제외하지 않는다.
- GitHub 이슈와 PR 템플릿은 실행 시점의 `.github/` 파일을 단일 진실 공급원으로 사용한다.
- push, 이슈 생성, PR 생성·갱신처럼 원격 상태를 바꾸는 작업은 사용자 승인 후 실행한다.

---

### Task 1: 전체 diff 검증 스킬

**Files:**
- Create: `settlement-service/.codex/skills/verify-project-changes/SKILL.md`
- Create: `settlement-service/.codex/skills/verify-project-changes/agents/openai.yaml`
- Modify: `settlement-service/.codex/skills/verify-settlement-rules/SKILL.md`
- Modify: `settlement-service/.codex/skills/verify-settlement-rules/agents/openai.yaml`

**Interfaces:**
- Consumes: base 브랜치, head 브랜치, 전체 변경 파일·diff·commit, 경로별 프로젝트 규칙
- Produces: `RULE / SCOPE / STATUS / FINDINGS` 형식의 규칙별 결과와 전체 코드 검토 결과

- [ ] **Step 1: 기존 실패를 검증 기준으로 기록**

`settlement-service/`와 `payment-service/`가 함께 바뀐 diff에서 기존 스킬이 정산 변경만 검증하는지 확인한다.

- [ ] **Step 2: 범용 검증 절차 작성**

다음 계약을 스킬에 명시한다.

```text
검증 대상 = base...HEAD 전체 diff + 관련 작업 트리 변경
규칙 탐색 = 변경 경로에 적용되는 AGENTS.md, CLAUDE.md, .claude/rules/*.md
규칙 없음 = 일반 정확성·회귀·테스트·보안 검토 수행
검증 불가 = PASS 금지, 확인하지 못한 항목과 위험 보고
```

독립적인 검사는 병렬 실행할 수 있고, 각 검사자는 하나의 규칙과 범위만 담당하도록 Claude `rule-checker.md`의 읽기 전용 계약을 반영한다.

- [ ] **Step 3: 정산 호환 스킬 범위 제한 제거**

`verify-settlement-rules`가 `verify-project-changes`를 필수 호출하도록 만들고 `settlement-service/` 변경만 검증한다는 문구를 제거한다.

- [ ] **Step 4: 구조 검증**

Run: `python3 /Users/taetaetae/.codex/skills/.system/skill-creator/scripts/quick_validate.py settlement-service/.codex/skills/verify-project-changes`와 같은 명령을 두 스킬 디렉터리에 실행
Expected: 두 스킬 모두 validation success

### Task 2: 범용 PR 생성 스킬

**Files:**
- Create: `settlement-service/.codex/skills/create-project-pr/SKILL.md`
- Create: `settlement-service/.codex/skills/create-project-pr/agents/openai.yaml`
- Modify: `settlement-service/.codex/skills/create-settlement-pr/SKILL.md`
- Modify: `settlement-service/.codex/skills/create-settlement-pr/agents/openai.yaml`

**Interfaces:**
- Consumes: `verify-project-changes` 결과, 현재 PR 템플릿, branch·commit·diff, GitHub 메타데이터
- Produces: 승인용 PR 전체 초안과 승인 후 생성 또는 기존 PR 갱신 결과

- [ ] **Step 1: Claude PR 스킬의 안전장치 반영**

실행 시점 템플릿 읽기, 인증·브랜치·중복 PR 점검, 검증 게이트, 담백한 본문, 승인 전 push 금지, 생성 후 메타데이터 재검증을 작성한다. 실제 저장소 템플릿 경로인 `.github/PULL_REQUEST_TEMPLATE.md`를 우선하고 다른 위치는 탐색 후 결정한다.

- [ ] **Step 2: 도메인 범위 조건 제거**

변경 파일이 어느 모듈에 있는지와 무관하게 PR을 준비하고 `verify-project-changes` 결과를 사용하도록 작성한다. 테스트 명령은 변경 모듈과 영향 범위에서 도출한다.

- [ ] **Step 3: 정산 호환 스킬 위임**

`create-settlement-pr`는 기존 호출을 보존하되 `create-project-pr`를 필수 호출하고 별도의 정산 경로 게이트를 두지 않는다.

- [ ] **Step 4: 구조 검증**

Run: `python3 /Users/taetaetae/.codex/skills/.system/skill-creator/scripts/quick_validate.py settlement-service/.codex/skills/create-project-pr`와 같은 명령을 두 스킬 디렉터리에 실행
Expected: 두 스킬 모두 validation success

### Task 3: 범용 이슈 생성 스킬

**Files:**
- Create: `settlement-service/.codex/skills/create-project-issue/SKILL.md`
- Create: `settlement-service/.codex/skills/create-project-issue/agents/openai.yaml`
- Modify: `settlement-service/.codex/skills/create-settlement-issue/SKILL.md`
- Modify: `settlement-service/.codex/skills/create-settlement-issue/agents/openai.yaml`

**Interfaces:**
- Consumes: 사용자 요청, `.github/ISSUE_TEMPLATE/`, 실제 저장소 라벨과 GitHub Project 필드
- Produces: 승인용 이슈 전체 초안과 승인 후 생성·필드 적용 결과

- [ ] **Step 1: Claude 이슈 스킬의 템플릿 처리 반영**

템플릿 종류 판정, frontmatter와 본문 분리, 제목 접두어·라벨·assignee CLI 변환, 필수 정보 일괄 확인, 담백한 문체를 작성한다.

- [ ] **Step 2: 저장소 메타데이터 안전장치 유지**

실제 라벨 존재 확인, `@me`, Type·Project·Status 조회, 전체 초안 승인, 부분 성공 보고 절차를 유지한다.

- [ ] **Step 3: 정산 호환 스킬 범위 제한 제거**

`create-settlement-issue`가 `create-project-issue`를 필수 호출하도록 만들고 다른 서비스 이슈를 거절하는 문구를 제거한다.

- [ ] **Step 4: 구조 검증**

Run: `python3 /Users/taetaetae/.codex/skills/.system/skill-creator/scripts/quick_validate.py settlement-service/.codex/skills/create-project-issue`와 같은 명령을 두 스킬 디렉터리에 실행
Expected: 두 스킬 모두 validation success

### Task 4: 라우팅·시나리오 검증과 PR 반영

**Files:**
- Modify: `settlement-service/AGENTS.md`
- Modify: `settlement-service/docs/superpowers/specs/2026-07-13-generic-pr-review-skills-design.md`
- Create: `settlement-service/docs/superpowers/plans/2026-07-13-generic-github-skills.md`

**Interfaces:**
- Consumes: 범용 스킬 세 개
- Produces: 명확한 스킬 라우팅과 재현 가능한 검증 결과

- [ ] **Step 1: Codex 라우팅 갱신**

일반 요청과 정산 요청 모두 PR·이슈·전체 diff 검증 범용 스킬로 연결한다.

- [ ] **Step 2: 금지 문구와 범위 계약 점검**

Run: `rg -n '다른 서비스 이슈는 만들지 않는다|settlement-service/ 변경만 검증|변경 파일이 settlement-service/ 범위' settlement-service/.codex/skills`
Expected: 관련 범위 제한 문구 없음

- [ ] **Step 3: 수정 후 시나리오 재시험**

product-only PR, settlement+payment 혼합 diff, product 이슈 요청을 독립 검증한다.
Expected: PR·이슈를 도메인 때문에 거절하지 않고 전체 diff와 경로별 규칙을 검토함

- [ ] **Step 4: 최종 diff 검증과 커밋**

Run: `git diff --check` 및 전체 스킬 validator
Expected: 오류 없음

Commit: `docs: GitHub 작업 스킬 범용화`

### Task 5: 정산 호환 진입점 제거

**Files:**
- Delete: `settlement-service/.codex/skills/create-settlement-pr/`
- Delete: `settlement-service/.codex/skills/create-settlement-issue/`
- Delete: `settlement-service/.codex/skills/verify-settlement-rules/`
- Modify: `settlement-service/AGENTS.md`
- Modify: `settlement-service/docs/superpowers/specs/2026-07-13-generic-pr-review-skills-design.md`

- [ ] **Step 1: 중복 이름 제거**

호환용 이름만 제공하던 정산 스킬 세 개를 삭제하고 범용 스킬 세 개만 유지한다.

- [ ] **Step 2: 라우팅과 문서 정리**

정산 요청도 범용 스킬로 직접 연결하고 호환 진입점 설명을 제거한다.

- [ ] **Step 3: 최종 검증**

Run: 범용 스킬 세 개 `quick_validate.py`, 제거된 스킬 이름 `rg`, `git diff --check`
Expected: 범용 스킬 3/3 validation success, 삭제된 스킬 참조 없음, diff 오류 없음
