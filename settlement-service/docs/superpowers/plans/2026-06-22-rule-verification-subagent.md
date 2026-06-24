# 룰 검증 서브에이전트 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR 직전, 변경 코드를 프로젝트 룰 6종 기준으로 병렬 서브에이전트가 검증하고, 통과 결과를 PR 체크리스트에 자동 체크하며, 위반이 있으면 PR 생성을 게이트로 막는다.

**Architecture:** 읽기 전용 `rule-checker` 서브에이전트 1종을 룰당 하나씩 6개 병렬 디스패치한다. `verify-rules` 스킬이 오케스트레이터로서 변경 파일 수집 → 6개 디스패치 → 집계 → 게이트 판정을 한다. `create-github-pr` 스킬이 본문 채우기 직전에 `verify-rules`를 호출한다. PR 템플릿 체크리스트는 6룰 1:1 매핑으로 교체한다.

**Tech Stack:** Claude Code 서브에이전트(`.claude/agents`), 스킬(`.claude/skills`), 마크다운, `gh`/`git`. 자동 단위 테스트 대상이 아니므로 검증은 실제 실행 시나리오로 한다.

## Global Constraints

- 모든 생성물은 저장소 루트가 아니라 **`settlement-service/` 하위**에 둔다. (CLAUDE.md)
- 서브에이전트는 `settlement-service/.claude/agents/`, 스킬은 `settlement-service/.claude/skills/`에 둔다.
- 룰 파일 6종 경로: `settlement-service/.claude/rules/{clean-architecture,domain-model,controller-exception,code-style,swagger,git-convention}.md`
- **룰 = 체크박스 1:1, 총 6개.** 항목을 더 잘게 쪼개지 않는다.
- 검증만 한다. **auto-fix 없음.** 위반 ≥1건이면 PR 생성 STOP.
- 변경 파일 한정(diff가 닿은 파일 통째로). 모듈 전체 스캔 안 함.
- 문구는 담백하게(AI 티 금지). 커밋 메시지는 `<타입>: <내용>` + `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- 디스크에 직접 추가한 agent/skill `.md`는 현재 세션에서 즉시 인식 안 될 수 있다(세션 재시작 필요).

---

### Task 1: PR 템플릿 체크리스트 룰 기반 교체

PR 템플릿의 단일 진실 공급원인 체크리스트를 6룰 매핑으로 바꾼다. `create-github-pr`·`verify-rules`가 모두 이 항목을 기준으로 동작한다.

**Files:**
- Modify: `.github/ISSUE_TEMPLATE/pull_request_template.md` (체크리스트 섹션 + 맨 아래 TODO 줄). **레포 루트**에 있다(모듈 하위 아님). 모듈(`settlement-service/`) cwd 기준으로는 `../.github/ISSUE_TEMPLATE/pull_request_template.md`.

**Interfaces:**
- Produces: 체크박스 라벨 6종(아래 텍스트). `verify-rules`의 룰→라벨 매핑(Task 3)이 이 라벨과 **글자 그대로** 일치해야 한다.

- [ ] **Step 1: 체크리스트 섹션 교체**

`## ☑️ 체크리스트 (Checklist)` 아래 기존 4개 항목(레이어 격리 / 단방향 의존 / 비용 방어 / 보안)을 아래로 통째 교체한다.

```markdown
## ☑️ 체크리스트 (Checklist)

- [ ] 클린아키텍처: 의존성 방향(presentation→application→domain←infrastructure)·포트&어댑터·계층 책임 준수
- [ ] 도메인 모델: 비즈니스 setter 금지·상태변경은 도메인 메서드·Lombok 허용 범위 준수
- [ ] Controller/예외: 얇은 컨트롤러(Repository 직접 접근 금지)·커스텀 예외·전역 예외 핸들러
- [ ] 코드 스타일: 네이밍 케이스·와일드카드 import 금지·빈 catch 금지
- [ ] Swagger: 표현계층 한정·@Operation/@ApiResponses/@Schema 명시
- [ ] Git 컨벤션: 커밋 메시지(`<타입>: <내용>`)·브랜치 명명 규칙
```

- [ ] **Step 2: 맨 아래 TODO 줄 제거**

`## ➕ 추가 정보 (Additional Information)` 아래의 `TODO: 추후 자가점검 항목은 아키텍처에 맞게 변동해야 합니다.` 줄을 삭제해, 해당 섹션을 빈 채로 둔다.

- [ ] **Step 3: 결과 확인**

Run(레포 루트에서): `cat .github/ISSUE_TEMPLATE/pull_request_template.md`
Expected: 체크리스트에 6개 룰 항목만 있고, TODO 줄이 사라졌다.

- [ ] **Step 4: 커밋**

```bash
git add .github/ISSUE_TEMPLATE/pull_request_template.md
git commit -m "$(printf 'docs: PR 템플릿 체크리스트를 프로젝트 룰 6종 기반으로 교체\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

### Task 2: rule-checker 서브에이전트 생성

단일 룰 파일 하나만 대조해 변경 파일의 위반을 탐지하는 읽기 전용 에이전트. 6번 병렬 디스패치된다.

**Files:**
- Create: `.claude/agents/rule-checker.md`

**Interfaces:**
- Consumes: 디스패치 프롬프트로 `RULE_NAME`, `RULE_FILE`, `INPUT`(변경 파일 목록 또는 git 메타데이터)를 받는다.
- Produces: 고정 텍스트 리포트 — 첫 줄 `RULE: <name>`, 둘째 줄 `STATUS: <PASS|FAIL|N/A>`, 이후 `VIOLATIONS:` + `- 파일:라인 — 사유` 목록. `verify-rules`(Task 3)가 이 포맷을 파싱한다.

- [ ] **Step 1: 에이전트 파일 작성**

`.claude/agents/rule-checker.md` 를 아래 내용 그대로 생성한다.

```markdown
---
name: rule-checker
description: 단일 프로젝트 룰 파일 하나를 기준으로 변경된 코드 파일들의 위반 여부만 검사하는 읽기 전용 검증 에이전트. verify-rules 스킬이 룰 6종을 병렬 검증할 때 룰당 하나씩 디스패치한다. 프롬프트로 받은 룰 하나에만 한정해 위반을 탐지하고 고정 포맷 리포트를 반환한다. 코드를 수정하지 않는다.
tools: Read, Grep, Glob, Bash
model: sonnet
---

# rule-checker — 단일 룰 위반 검증 에이전트

너는 **하나의 프로젝트 룰 파일**을 기준으로, 주어진 입력이 그 룰을 위반하는지만 검사하는 읽기 전용
검증자다. 코드를 고치지 않는다. 네가 받은 룰 외의 다른 룰 위반은 보고하지 않는다.

## 입력 (디스패치 프롬프트로 받는다)

- `RULE_NAME`: 검증할 룰 이름 (예: `clean-architecture`)
- `RULE_FILE`: 룰 정의 파일 경로 (예: `.claude/rules/clean-architecture.md`)
- `INPUT`: 검사 대상.
  - 대부분의 룰: 변경 파일 경로 목록(`CHANGED_FILES`).
  - `git-convention` 룰: 브랜치명 + 커밋 메시지 목록.

## 절차

1. `RULE_FILE`을 Read로 읽어 규칙을 파악한다.
2. `INPUT`을 본다.
   - 파일 목록이면 각 파일을 Read로 통째로 읽는다.
   - git 메타데이터면 브랜치명·커밋 메시지를 규칙(브랜치 명명 `<타입>/#<이슈>-<내용>`, 커밋
     `<타입>: <내용>`)과 대조한다.
3. 입력이 `RULE_FILE`의 규칙을 어기는지 판단한다. 위반이면 `대상:위치 — 사유` 형태로 기록한다.
   - 파일 위반은 `파일경로:라인 — 사유`. 라인이 모호하면 가장 가까운 위치를 적는다.
   - 위반 사유는 어떤 규칙을 어겼는지 한 줄로 적는다.
4. 이 룰의 적용 대상이 입력에 하나도 없으면(예: `swagger` 룰인데 controller·DTO 변경이 없음)
   `STATUS`를 `N/A`로 한다.

## 출력 (이 포맷을 그대로 반환한다. 앞뒤에 다른 말을 붙이지 않는다)

```
RULE: <RULE_NAME>
STATUS: <PASS | FAIL | N/A>
VIOLATIONS:
- <대상:위치 — 사유>
```

- 위반이 없으면 `STATUS: PASS`, `VIOLATIONS:` 아래는 비운다.
- 적용 대상이 없으면 `STATUS: N/A`, `VIOLATIONS:` 비운다.
- 위반이 하나라도 있으면 `STATUS: FAIL`.

## 주의

- 추측으로 위반을 지어내지 않는다. `RULE_FILE`에 근거가 있는 위반만 보고한다.
- 네 룰과 무관한 개선 제안·일반 코드 리뷰 코멘트를 하지 않는다. 위반 탐지만 한다.
```

- [ ] **Step 2: 포맷·로딩 확인**

Run: `head -6 .claude/agents/rule-checker.md`
Expected: frontmatter에 `name: rule-checker`, `tools: Read, Grep, Glob, Bash`, `model: sonnet`이 보인다.
참고: 디스크에 직접 만든 agent는 세션 재시작 후 `subagent_type: rule-checker`로 디스패치 가능하다. Task 5 검증 전 세션 재시작이 필요할 수 있다.

- [ ] **Step 3: 커밋**

```bash
git add .claude/agents/rule-checker.md
git commit -m "$(printf 'feat: 룰 위반 검증용 rule-checker 서브에이전트 추가\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

### Task 3: verify-rules 스킬 생성

오케스트레이터. 변경 파일 수집 → rule-checker 6개 병렬 디스패치 → 집계 → 게이트 판정 → 체크리스트 렌더.

**Files:**
- Create: `.claude/skills/verify-rules/SKILL.md`

**Interfaces:**
- Consumes: Task 1의 체크박스 라벨 6종, Task 2의 `rule-checker` 에이전트(`subagent_type: rule-checker`)와 그 출력 포맷.
- Produces: 게이트 결과(통과/STOP)와 통과 시 6개 `[x]` 체크리스트 텍스트. `create-github-pr`(Task 4)가 이 결과를 본문 체크리스트에 반영한다.

- [ ] **Step 1: 스킬 파일 작성**

`.claude/skills/verify-rules/SKILL.md` 를 아래 내용 그대로 생성한다.

````markdown
---
name: verify-rules
description: >-
  현재 브랜치의 변경 코드를 프로젝트 룰 6종(clean-architecture, domain-model,
  controller-exception, code-style, swagger, git-convention) 기준으로 rule-checker
  서브에이전트 6개를 병렬 디스패치해 검증합니다. 위반이 하나라도 있으면 게이트로 막고 위반 목록을
  보여줍니다. 사용자가 "룰 검증해줘", "컨벤션 어긴 데 없는지 봐줘", "PR 전에 룰 체크"라고 하거나,
  create-github-pr 스킬이 PR 본문을 채우기 직전에 호출합니다. 코드를 수정하지는 않습니다.
---

# 룰 검증 게이트 (verify-rules)

변경 코드가 프로젝트 룰을 지키는지 검증한다. 룰당 `rule-checker` 서브에이전트 하나씩 6개를
**병렬로** 디스패치하고, 결과를 모아 게이트를 판정한다. **위반이 하나라도 있으면 통과시키지 않는다.**
이 스킬은 검증만 한다 — 코드를 고치지 않고, PR을 만들지도 않는다.

## 룰 → 체크박스 매핑

PR 템플릿(`.github/ISSUE_TEMPLATE/pull_request_template.md`) 체크리스트 6항목과 1:1로 대응한다.
라벨은 템플릿을 단일 진실 공급원으로 보고, 검증 시점에 템플릿에서 읽어 맞춘다.

| RULE_NAME | RULE_FILE | 입력 | 체크박스(템플릿 라벨 앞부분) |
| --- | --- | --- | --- |
| clean-architecture | `.claude/rules/clean-architecture.md` | 변경 파일 | 클린아키텍처 |
| domain-model | `.claude/rules/domain-model.md` | 변경 파일 | 도메인 모델 |
| controller-exception | `.claude/rules/controller-exception.md` | 변경 파일 | Controller/예외 |
| code-style | `.claude/rules/code-style.md` | 변경 파일 | 코드 스타일 |
| swagger | `.claude/rules/swagger.md` | 변경 파일 | Swagger |
| git-convention | `.claude/rules/git-convention.md` | 브랜치명 + 커밋 메시지 | Git 컨벤션 |

## 워크플로우

### 1. 변경분 수집

base 브랜치를 정하고(현재 브랜치가 `feat/*`·`fix/*` 등 작업 브랜치면 base는 `develop`) 그에 대비한
변경을 모은다. base 판정은 create-github-pr과 동일 기준을 쓴다.

```bash
git rev-parse --abbrev-ref HEAD            # 현재(head) 브랜치
git diff <base>...HEAD --name-only         # 변경 파일 목록 (CHANGED_FILES)
git log <base>..HEAD --format="%s"         # 커밋 제목 (git-convention 입력)
```

- 변경 파일이 하나도 없으면 검증할 게 없다. 알리고 멈춘다.
- 현재 브랜치가 base(develop/main) 자체면 검증 대상 브랜치가 아니다. 알리고 멈춘다.

### 2. rule-checker 6개 병렬 디스패치

**한 메시지에 Task 6개를 동시에** 띄운다(`subagent_type: rule-checker`). 각 프롬프트에 아래를 채운다.

- 5개(clean-architecture, domain-model, controller-exception, code-style, swagger):
  `RULE_NAME`, `RULE_FILE`, `INPUT = CHANGED_FILES`(1단계 변경 파일 목록).
- git-convention 1개: `RULE_NAME=git-convention`, `RULE_FILE`, `INPUT = 브랜치명 + 커밋 제목 목록`.

디스패치 프롬프트 틀:

```
RULE_NAME: clean-architecture
RULE_FILE: .claude/rules/clean-architecture.md
INPUT (CHANGED_FILES):
- src/main/java/.../Foo.java
- src/main/java/.../Bar.java

위 룰 파일 하나만 기준으로 변경 파일의 위반을 검사하고, 지정된 출력 포맷으로만 답하라.
```

### 3. 집계 + 게이트 판정

각 rule-checker가 돌려준 `RULE / STATUS / VIOLATIONS` 리포트를 모은다.

- 6개 모두 `PASS` 또는 `N/A` → **게이트 통과.**
- 하나라도 `FAIL` → **게이트 STOP.**

### 4. 결과 출력

**통과 시** — 체크리스트를 렌더해 보여준다(아래는 `create-github-pr`가 본문에 그대로 쓸 수 있다).

```
☑️ 룰 검증 통과 — 6/6
- [x] 클린아키텍처: ...
- [x] 도메인 모델: ...
- [x] Controller/예외: ...
- [x] 코드 스타일: ...
- [x] Swagger: ...
- [x] Git 컨벤션: ...
```

- `N/A`였던 룰은 라벨 끝에 `(해당 변경 없음)`을 덧붙이되 `[x]`로 둔다.

**STOP 시** — 위반을 그대로 보여주고, 수정 후 다시 돌리라고 안내한 뒤 멈춘다. 체크는 하지 않는다.

```
⛔ 룰 검증 실패 — 다음을 고친 뒤 다시 검증하세요.

[Controller/예외]
- src/main/java/.../SettlementController.java:42 — Repository 직접 주입 (포트 미경유)

[도메인 모델]
- src/main/java/.../Settlement.java:18 — @Setter 사용 (비즈니스 setter 금지)
```

## 다른 스킬과의 경계

- **create-github-pr**: 이 스킬을 PR 본문 채우기 직전에 호출한다. 게이트를 통과해야 PR로 진행한다.
- **code-review / review**: 코드 품질 전반을 리뷰하는 스킬이다. 이 스킬은 **프로젝트 룰 6종 위반만**
  본다. 범위가 다르다.
- 이 스킬은 코드를 수정하지 않는다(검증·게이트·리포트까지만). 수정은 사용자가 한다.
````

- [ ] **Step 2: 라벨 일치 확인**

Run(레포 루트에서): `grep -E '^\- \[ \]' .github/ISSUE_TEMPLATE/pull_request_template.md`
Expected: 6개 라벨 앞부분(클린아키텍처 / 도메인 모델 / Controller/예외 / 코드 스타일 / Swagger / Git 컨벤션)이 스킬의 매핑 표와 글자 그대로 일치한다. 불일치 시 스킬 표를 템플릿에 맞춘다.

- [ ] **Step 3: 커밋**

```bash
git add .claude/skills/verify-rules/SKILL.md
git commit -m "$(printf 'feat: 룰 검증 오케스트레이터 verify-rules 스킬 추가\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

### Task 4: create-github-pr에 verify-rules 게이트 연동

PR 본문 채우기 직전에 검증 게이트를 끼운다. 단계 번호 교차참조(4단계 등)를 깨지 않도록 `2.5` 단계로 삽입한다.

**Files:**
- Modify: `.claude/skills/create-github-pr/SKILL.md`

**Interfaces:**
- Consumes: `verify-rules` 스킬(Task 3)의 게이트 결과와 통과 시 체크리스트 텍스트.

- [ ] **Step 1: 게이트 단계 삽입**

`### 2. 컨텍스트 수집 (git)` 섹션의 마지막 bullet(`테스트 계획 섹션을 채울 근거로 쓴다.`) 바로 **다음**, `### 3. 템플릿 읽기` **앞**에 아래 섹션을 추가한다.

```markdown
### 2.5. 룰 검증 게이트 (verify-rules) — 통과 못 하면 중단

본문을 채우기 전에 **verify-rules 스킬을 호출**해 변경 코드가 프로젝트 룰 6종을 지키는지 검증한다.

- verify-rules가 **위반(FAIL)을 하나라도 보고하면 PR을 만들지 않는다.** 위반 목록(대상:위치·사유)을
  사용자에게 그대로 보여주고, 수정 후 다시 시도하도록 안내한 뒤 멈춘다. `git push`도 `gh pr create`도
  하지 않는다.
- 전 룰이 PASS/N/A면 게이트 통과다. verify-rules가 만든 체크리스트 결과(룰별 `[x]`)를 보관했다가
  4단계 체크리스트 섹션에 그대로 반영한다.
```

- [ ] **Step 2: 체크리스트 작성 지침 교체**

`### 4. 본문 채우기` 안의 `- **체크리스트**:` bullet 블록(아래 "근거 없이 체크하지 않는다 — 잘못된 체크는 리뷰어를 오도한다." 까지)을 아래로 교체한다.

기존:
```markdown
- **체크리스트**: 템플릿의 각 항목을 diff 근거로 하나씩 판단한다.
  - 변경 내용으로 충족이 확인되면 `[x]`로 체크하고, 옆에 근거를 한 줄 덧붙인다
    (예: `- [x] 보안: API Key 하드코딩 없음 (diff에 키 문자열·.env 값 추가 없음)`).
  - 확인이 안 되거나 애매하면 `[ ]`로 비워둔다. 근거 없이 체크하지 않는다 — 잘못된 체크는
    리뷰어를 오도한다.
```

교체:
```markdown
- **체크리스트**: 2.5단계 verify-rules가 통과시킨 결과를 그대로 반영한다. 게이트를 통과했으므로
  6개 룰 항목은 `[x]`로 체크한다. N/A였던 항목은 옆에 `(해당 변경 없음)`을 덧붙인다. 스킬이 임의로
  룰 위반을 다시 판단하지 않는다 — 판단은 verify-rules가 이미 했다.
```

- [ ] **Step 3: 스킬 경계에 verify-rules 추가**

`## 다른 스킬과의 경계` 섹션 끝에 아래 bullet을 추가한다.

```markdown
- **verify-rules**: PR 본문을 채우기 전에 이 스킬이 호출하는 룰 검증 게이트다. 통과하지 못하면
  PR을 만들지 않는다.
```

- [ ] **Step 4: 변경 확인**

Run(모듈에서): `grep -n "2.5. 룰 검증 게이트\|verify-rules" .claude/skills/create-github-pr/SKILL.md`
Expected: 2.5 게이트 섹션·체크리스트 지침·경계 bullet에 verify-rules 참조가 보인다.

- [ ] **Step 5: 커밋**

```bash
git add .claude/skills/create-github-pr/SKILL.md
git commit -m "$(printf 'feat: create-github-pr에 verify-rules 룰 검증 게이트 연동\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

### Task 5: 통합 검증 (수동 시나리오)

실제 디스패치가 도는지, 위반 게이트와 통과 체크가 의도대로인지 확인한다. 자동 테스트가 아니므로 일회성 fixture로 돌려보고 정리한다. **이 태스크는 커밋하지 않는다.**

**Files:**
- Temp(검증 후 폐기): `src/main/java/com/prompthub/settlement/_fixture/RuleViolationSample.java`

**Interfaces:**
- Consumes: Task 2·3·4의 산출물. 세션 재시작으로 `rule-checker` 에이전트가 로딩돼 있어야 한다.

- [ ] **Step 1: 위반 fixture 생성**

명백한 룰 위반 1개 이상을 가진 임시 파일을 만든다(도메인 모델에 `@Setter` + 와일드카드 import → domain-model·code-style 동시 위반).

```java
package com.prompthub.settlement._fixture;

import jakarta.persistence.*;          // 와일드카드 import (code-style 위반)
import lombok.Setter;

@Entity
@Setter                                 // 비즈니스 setter 금지 (domain-model 위반)
public class RuleViolationSample {
    @Id
    private Long id;
    private String status;
}
```

- [ ] **Step 2: verify-rules 단독 실행 — FAIL 게이트 확인**

verify-rules 스킬을 호출한다(예: "룰 검증해줘"). 변경 파일에 위 fixture가 잡혀야 한다.
Expected: 게이트 STOP. `code-style`과 `domain-model`이 `FAIL`로 뜨고, 위반에 `RuleViolationSample.java`의 와일드카드 import·`@Setter`가 대상:위치·사유로 출력된다. PR은 만들어지지 않는다.

- [ ] **Step 3: fixture 제거 — PASS 확인**

fixture 파일을 삭제한다.

```bash
rm src/main/java/com/prompthub/settlement/_fixture/RuleViolationSample.java
rmdir src/main/java/com/prompthub/settlement/_fixture 2>/dev/null || true
```

verify-rules를 다시 호출한다.
Expected: 남은 실제 변경분 기준으로 전 룰 `PASS`/`N/A`. 통과 시 6개 `[x]` 체크리스트가 렌더된다. (남은 변경분에 실제 위반이 있으면 그건 정상적인 FAIL이므로 별도로 다룬다.)

- [ ] **Step 4: create-github-pr 연동 확인(선택)**

실제 PR을 만들 상황이면 create-github-pr를 호출해, 2.5 게이트가 먼저 돌고 통과 후에만 본문 작성으로 넘어가는지, 체크리스트가 `[x]`로 채워지는지 확인한다. (실제 PR 생성 전 승인 단계에서 멈출 수 있다.)

- [ ] **Step 5: 정리**

fixture가 git에 남지 않았는지 확인한다.
Run: `git status -s`
Expected: `_fixture` 관련 변경이 없다.

---

## 비고

- `rule-checker`의 `model: sonnet`은 비용·속도 절충값이다. 클린아키텍처처럼 판단이 까다로운 룰에서 누락이 보이면 해당 디스패치만 상위 모델로 올리는 조정이 가능하다.
- 세션에서 디스크로 직접 추가한 agent/skill은 즉시 인식되지 않을 수 있다. Task 5 검증 전 세션 재시작을 권장한다.
