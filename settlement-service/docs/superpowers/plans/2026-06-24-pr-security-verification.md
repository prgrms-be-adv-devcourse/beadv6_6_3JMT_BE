# PR 보안 검증 게이트(7번째 룰) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR 생성 전 보안 위반(시크릿 파일 커밋·하드코딩 시크릿·민감정보 노출·.gitignore 누락)을 7번째 룰로 자동 검증하고 체크리스트에 명시한다.

**Architecture:** 기존 `verify-rules` 게이트(룰 N개를 `rule-checker` 서브에이전트 N개로 병렬 검증)에 보안을 7번째 룰로 추가한다. `.claude/rules/security.md` 룰 파일을 신규 작성하고, verify-rules·rule-checker·PR 템플릿·create-github-pr의 관련 텍스트(6→7)를 갱신한다. 코드 변경이 아니라 마크다운(룰·스킬·에이전트·템플릿) 편집이므로, 각 태스크는 "편집 → 내용 검증(grep/read)"으로 마무리한다.

**Tech Stack:** Markdown(Claude Code 룰·스킬·에이전트 정의), git, gh.

## Global Constraints

- 모든 신규 산출물은 `settlement-service/` 하위에 둔다(저장소 루트 아님).
- 룰 내용은 스킬에 복붙하지 않고 `.claude/rules/*.md` 파일에 둔다(단일 진실 공급원).
- 게이트 상태는 `PASS / FAIL / N/A` 바이너리만 쓴다(WARN 신설 금지).
- 시크릿/민감정보 스캔은 **diff 추가 라인(`+`)만** 대상으로 한다(파일 커밋·.gitignore 검사는 파일 목록 기준).
- `application*.yml`/`application*.properties`의 로컬 설정값(localhost·로컬 DB·포트 등)은 위반이 아니다.
- 커밋 메시지는 `<타입>: <내용>` 컨벤션을 따르고, Claude co-author trailer를 넣지 않는다.
- 현재 브랜치: `feat/#14-settlement-batch-execution`. 저장소 루트: `/Users/taetaetae/IdeaProjects/beadv6_6_3JMT_BE`.

---

## File Structure

- `settlement-service/.claude/rules/security.md` — **신규.** 보안 룰 정의(검사 4종 + 예외 + 검사 방법).
- `settlement-service/.claude/agents/rule-checker.md` — **수정.** 6종→7종 텍스트, 룰이 검사 방법을 지정하면 따른다는 절차 추가.
- `settlement-service/.claude/skills/verify-rules/SKILL.md` — **수정.** 6→7(설명·테이블·디스패치·집계·렌더).
- `.github/ISSUE_TEMPLATE/pull_request_template.md` — **수정.** 체크리스트 7번째 항목 추가.
- `settlement-service/.claude/skills/create-github-pr/SKILL.md` — **수정.** 6→7 텍스트 갱신.

---

### Task 1: 보안 룰 파일 신규 작성

**Files:**
- Create: `settlement-service/.claude/rules/security.md`

**Interfaces:**
- Produces: 룰 파일. `rule-checker`가 `RULE_FILE`로 Read하고, verify-rules 매핑 테이블이 `security` 룰로 참조한다. 체크박스 라벨 앞부분은 `보안`.

- [ ] **Step 1: 룰 파일 작성**

`settlement-service/.claude/rules/security.md` 생성, 아래 내용 그대로:

````markdown
# 보안 컨벤션

PR에 시크릿·민감정보가 새어 들어가는 것을 막는 규칙을 정의한다. 이 룰은 `rule-checker` 가
verify-rules 게이트에서 검증하며, 위반이 있으면 PR 생성을 막는다.

> 관련 문서: 민감정보 노출 금지의 표현 계층 관점은 `controller-exception.md` §2-3 참고.

## 0. 검사 방법 (검사기 지침)

이 룰은 변경 파일 목록뿐 아니라 **이번 브랜치가 추가한 diff 라인**과 `.gitignore` 내용을 본다.
검사기(`rule-checker`)는 INPUT 파일 목록을 통째로 읽는 대신, 아래를 직접 수행한다.

```bash
git diff <base>...HEAD --name-only    # §1: 추가/변경된 파일 경로
git diff <base>...HEAD                 # §2·§3: 추가 라인('+'로 시작하는 줄)만 본다
cat .gitignore                         # §4: 무시 패턴 확인
```

- `<base>` 는 작업 브랜치(`feat/*`·`fix/*` 등)면 `develop` 을 쓴다(verify-rules 와 동일 기준).
- §2·§3 은 **`+` 로 시작하는 추가 라인만** 검사한다. 기존 코드에 있던 값으로 PR 을 막지 않는다.

## 1. 시크릿 파일 커밋 금지

다음 패턴의 파일이 이번 변경으로 **추가**되면 위반(FAIL)이다.

- `.env`, `.env.*`
- `*.pem`, `*.key`, `*.p12`, `*.keystore`, `*.jks`
- `id_rsa`, `id_dsa`, `*.ppk`
- 파일명에 `credential` / `secret` 이 들어간 `*.yml` · `*.yaml` · `*.json` · `*.properties`

판단은 `git diff <base>...HEAD --name-only` 의 경로로 한다.

## 2. 코드 내 하드코딩 시크릿 금지

diff 추가 라인에서 다음이 코드에 평문으로 박히면 위반이다.

- API 키 / 액세스 토큰 / 시크릿 키 (예: `AKIA...` AWS 액세스 키, `sk-...` 류)
- 비밀번호 리터럴 (예: `password = "p@ssw0rd"`)
- `Authorization: Bearer <긴 토큰>` 형태의 하드코딩 토큰
- `-----BEGIN ... PRIVATE KEY-----` 블록

## 3. 민감정보 로깅·노출 금지

diff 추가 라인에서 다음이 보이면 위반이다.

- 비밀번호·토큰·주민등록번호·카드번호 등을 `log.*(...)` 로 출력
- 위 정보나 시스템 내부 상세(스택 트레이스·SQL·드라이버 메시지)를 예외 메시지·HTTP 응답으로
  그대로 클라이언트에 노출 (`controller-exception.md` §2-3 과 연결)

## 4. .gitignore 누락 점검

`.gitignore` 에 아래 민감 파일 패턴이 빠져 있으면 위반이다. (지금 커밋되지 않았더라도 예방 차원)

- `.env` (또는 `.env.*`)
- `*.pem` · `*.key` (개인 키 류)

## 5. 예외 — 로컬 설정값은 위반이 아니다

`application*.yml` · `application*.properties` 의 **로컬·개발용 설정값은 위반이 아니다.**

- `localhost`, `127.0.0.1`, 로컬 DB URL/계정, 포트 번호, 개발용 더미 값 등
- 위반은 **실제 운영 시크릿**(외부 서비스 API 키, 운영 DB 비밀번호 등)이 평문으로 박힌 경우만이다.

설정 키 이름에 `password`·`secret` 이 있어도, 값이 로컬/개발용이거나 환경변수 치환
(`${DB_PASSWORD}`)이면 위반이 아니다.
````

- [ ] **Step 2: 작성 내용 검증**

Run: `grep -nE "^## [0-9]" settlement-service/.claude/rules/security.md`
Expected: `## 0` ~ `## 5` 6개 절 제목이 출력된다.

- [ ] **Step 3: 커밋**

```bash
git add settlement-service/.claude/rules/security.md
git commit -m "docs: 보안 룰(security.md) 추가 — 시크릿 커밋·하드코딩·노출·gitignore 검사"
```

---

### Task 2: rule-checker 에이전트 — 7종 반영 + 룰별 검사 방법 존중

**Files:**
- Modify: `settlement-service/.claude/agents/rule-checker.md`

**Interfaces:**
- Consumes: Task 1의 `security.md`(검사 방법을 §0에 명시).
- Produces: 보안 룰을 받아도 diff 기반으로 검사하는 rule-checker.

- [ ] **Step 1: description의 "6종" → "7종"**

`old_string`:
```
description: 단일 프로젝트 룰 파일 하나를 기준으로 변경된 코드 파일들의 위반 여부만 검사하는 읽기 전용 검증 에이전트. verify-rules 스킬이 룰 6종을 병렬 검증할 때 룰당 하나씩 디스패치한다. 프롬프트로 받은 룰 하나에만 한정해 위반을 탐지하고 고정 포맷 리포트를 반환한다. 코드를 수정하지 않는다.
```
`new_string`:
```
description: 단일 프로젝트 룰 파일 하나를 기준으로 변경된 코드 파일들의 위반 여부만 검사하는 읽기 전용 검증 에이전트. verify-rules 스킬이 룰 7종을 병렬 검증할 때 룰당 하나씩 디스패치한다. 프롬프트로 받은 룰 하나에만 한정해 위반을 탐지하고 고정 포맷 리포트를 반환한다. 코드를 수정하지 않는다.
```

- [ ] **Step 2: 절차 2번에 "룰이 검사 방법을 지정하면 따른다" 추가**

`old_string`:
```
2. `INPUT`을 본다.
   - 파일 목록이면 각 파일을 Read로 통째로 읽는다.
   - git 메타데이터면 브랜치명·커밋 메시지를 규칙(브랜치 명명 `<타입>/#<이슈>-<내용>`, 커밋
     `<타입>: <내용>`)과 대조한다.
```
`new_string`:
```
2. `INPUT`을 본다.
   - 파일 목록이면 각 파일을 Read로 통째로 읽는다.
   - git 메타데이터면 브랜치명·커밋 메시지를 규칙(브랜치 명명 `<타입>/#<이슈>-<내용>`, 커밋
     `<타입>: <내용>`)과 대조한다.
   - 단, `RULE_FILE`이 자체 검사 방법(예: `git diff`로 추가 라인만 보기, `.gitignore` 읽기)을
     지정하면 그 방법을 우선해 따른다. (예: `security` 룰)
```

- [ ] **Step 3: 변경 검증**

Run: `grep -nE "7종|검사 방법을 지정" settlement-service/.claude/agents/rule-checker.md`
Expected: description의 `7종`, 절차의 `검사 방법을 지정` 두 줄이 출력된다.

- [ ] **Step 4: 커밋**

```bash
git add settlement-service/.claude/agents/rule-checker.md
git commit -m "docs: rule-checker 룰 7종 반영·룰별 검사 방법 존중 절차 추가"
```

---

### Task 3: verify-rules 스킬 — 7번째 룰 통합

**Files:**
- Modify: `settlement-service/.claude/skills/verify-rules/SKILL.md`

**Interfaces:**
- Consumes: Task 1 `security.md`, Task 2 rule-checker.
- Produces: security를 7번째로 병렬 검증하고 7/7 게이트로 판정하는 verify-rules. create-github-pr이 호출한다.

- [ ] **Step 1: description의 "6종"·목록·"6개" 갱신**

`old_string`:
```
  현재 브랜치의 변경 코드를 프로젝트 룰 6종(clean-architecture, domain-model,
  controller-exception, code-style, swagger, git-convention) 기준으로 rule-checker
  서브에이전트 6개를 병렬 디스패치해 검증합니다. 위반이 하나라도 있으면 게이트로 막고 위반 목록을
```
`new_string`:
```
  현재 브랜치의 변경 코드를 프로젝트 룰 7종(clean-architecture, domain-model,
  controller-exception, code-style, swagger, git-convention, security) 기준으로 rule-checker
  서브에이전트 7개를 병렬 디스패치해 검증합니다. 위반이 하나라도 있으면 게이트로 막고 위반 목록을
```

- [ ] **Step 2: 본문 도입부 "6개" → "7개"**

`old_string`:
```
변경 코드가 프로젝트 룰을 지키는지 검증한다. 룰당 `rule-checker` 서브에이전트 하나씩 6개를
```
`new_string`:
```
변경 코드가 프로젝트 룰을 지키는지 검증한다. 룰당 `rule-checker` 서브에이전트 하나씩 7개를
```

- [ ] **Step 3: 매핑 테이블에 security 행 추가**

`old_string`:
```
| swagger | `settlement-service/.claude/rules/swagger.md` | 변경 파일 | Swagger |
| git-convention | `settlement-service/.claude/rules/git-convention.md` | 브랜치명 + 커밋 메시지 | Git 컨벤션 |
```
`new_string`:
```
| swagger | `settlement-service/.claude/rules/swagger.md` | 변경 파일 | Swagger |
| git-convention | `settlement-service/.claude/rules/git-convention.md` | 브랜치명 + 커밋 메시지 | Git 컨벤션 |
| security | `settlement-service/.claude/rules/security.md` | 변경 파일 + diff(추가 라인) + .gitignore | 보안 |
```

- [ ] **Step 4: 디스패치 단계 제목·문구·입력 설명 갱신**

`old_string`:
```
### 2. rule-checker 6개 병렬 디스패치

**한 메시지에 Task 6개를 동시에** 띄운다(`subagent_type: rule-checker`). 각 프롬프트에 아래를 채운다.

- 5개(clean-architecture, domain-model, controller-exception, code-style, swagger):
  `RULE_NAME`, `RULE_FILE`, `INPUT = CHANGED_FILES`(1단계 변경 파일 목록).
- git-convention 1개: `RULE_NAME=git-convention`, `RULE_FILE`, `INPUT = 브랜치명 + 커밋 제목 목록`.
```
`new_string`:
```
### 2. rule-checker 7개 병렬 디스패치

**한 메시지에 Task 7개를 동시에** 띄운다(`subagent_type: rule-checker`). 각 프롬프트에 아래를 채운다.

- 5개(clean-architecture, domain-model, controller-exception, code-style, swagger):
  `RULE_NAME`, `RULE_FILE`, `INPUT = CHANGED_FILES`(1단계 변경 파일 목록).
- git-convention 1개: `RULE_NAME=git-convention`, `RULE_FILE`, `INPUT = 브랜치명 + 커밋 제목 목록`.
- security 1개: `RULE_NAME=security`, `RULE_FILE`, `INPUT = CHANGED_FILES`. 프롬프트에 "diff 추가 라인과
  `.gitignore`도 직접 확인하라(룰 §0 검사 방법을 따른다)"를 명시한다. base 는 1단계 기준과 동일.
```

- [ ] **Step 5: 게이트 판정 "6개" → "7개"**

`old_string`:
```
- 6개 모두 `PASS` 또는 `N/A` → **게이트 통과.**
```
`new_string`:
```
- 7개 모두 `PASS` 또는 `N/A` → **게이트 통과.**
```

- [ ] **Step 6: 통과 렌더 "6/6" → "7/7" + 보안 줄 추가**

`old_string`:
```
☑️ 룰 검증 통과 — 6/6
- [x] 클린아키텍처: ...
- [x] 도메인 모델: ...
- [x] Controller/예외: ...
- [x] 코드 스타일: ...
- [x] Swagger: ... (해당 변경 없음)
- [x] Git 컨벤션: ...
```
`new_string`:
```
☑️ 룰 검증 통과 — 7/7
- [x] 클린아키텍처: ...
- [x] 도메인 모델: ...
- [x] Controller/예외: ...
- [x] 코드 스타일: ...
- [x] Swagger: ... (해당 변경 없음)
- [x] Git 컨벤션: ...
- [x] 보안: ...
```

- [ ] **Step 7: 경계 설명 "6종" → "7종"**

`old_string`:
```
- **code-review / review**: 코드 품질 전반을 리뷰하는 스킬이다. 이 스킬은 **프로젝트 룰 6종 위반만**
```
`new_string`:
```
- **code-review / review**: 코드 품질 전반을 리뷰하는 스킬이다. 이 스킬은 **프로젝트 룰 7종 위반만**
```

- [ ] **Step 8: 변경 검증**

Run: `grep -nE "6종|6개|6/6|6 동시|하나씩 6" settlement-service/.claude/skills/verify-rules/SKILL.md`
Expected: 아무 줄도 출력되지 않는다(모든 6 표기가 7로 바뀜).

Run: `grep -nE "security|보안|7종|7개|7/7" settlement-service/.claude/skills/verify-rules/SKILL.md`
Expected: 매핑 테이블 행·디스패치 설명·게이트·렌더·경계에 security/보안/7 표기가 보인다.

- [ ] **Step 9: 커밋**

```bash
git add settlement-service/.claude/skills/verify-rules/SKILL.md
git commit -m "docs: verify-rules에 보안 룰 7번째 통합(7종 병렬 검증·7/7 게이트)"
```

---

### Task 4: PR 템플릿 체크리스트 7번째 항목

**Files:**
- Modify: `.github/ISSUE_TEMPLATE/pull_request_template.md`

**Interfaces:**
- Consumes: verify-rules 매핑 테이블의 "보안" 체크박스 라벨(Task 3).
- Produces: 체크리스트 7번째 항목. create-github-pr이 본문 채울 때 읽는다.

- [ ] **Step 1: 체크리스트 마지막 항목 뒤에 보안 줄 추가**

`old_string`:
```
- [ ] Git 컨벤션: 커밋 메시지(`<타입>: <내용>`)·브랜치 명명 규칙
```
`new_string`:
```
- [ ] Git 컨벤션: 커밋 메시지(`<타입>: <내용>`)·브랜치 명명 규칙
- [ ] 보안: .env·시크릿 파일 미커밋·코드 내 하드코딩 시크릿 없음·민감정보 로깅/노출 없음·.gitignore 누락 없음
```

- [ ] **Step 2: 변경 검증**

Run: `grep -nE "^- \[ \] " .github/ISSUE_TEMPLATE/pull_request_template.md | wc -l`
Expected: `7` (체크리스트 항목 7개).

Run: `grep -n "보안:" .github/ISSUE_TEMPLATE/pull_request_template.md`
Expected: 보안 항목 한 줄이 출력된다.

- [ ] **Step 3: 커밋**

```bash
git add .github/ISSUE_TEMPLATE/pull_request_template.md
git commit -m "docs: PR 템플릿 체크리스트에 보안 검증 항목 추가"
```

---

### Task 5: create-github-pr 스킬 — 7종 수치 반영

**Files:**
- Modify: `settlement-service/.claude/skills/create-github-pr/SKILL.md`

**Interfaces:**
- Consumes: verify-rules(7종 게이트, Task 3), PR 템플릿(7항목, Task 4).
- Produces: 워크플로우 변경 없이 수치만 7종으로 맞춘 PR 스킬. (2.5단계가 verify-rules를 부르므로 보안 게이트는 자동 포함.)

- [ ] **Step 1: 2.5단계 "룰 6종" → "룰 7종"**

`old_string`:
```
본문을 채우기 전에 **verify-rules 스킬을 호출**해 변경 코드가 프로젝트 룰 6종을 지키는지 검증한다.
```
`new_string`:
```
본문을 채우기 전에 **verify-rules 스킬을 호출**해 변경 코드가 프로젝트 룰 7종을 지키는지 검증한다.
```

- [ ] **Step 2: 4단계 체크리스트 "6개 룰 항목" → "7개 룰 항목"**

`old_string`:
```
  6개 룰 항목은 `[x]`로 체크한다. N/A였던 항목은 옆에 `(해당 변경 없음)`을 덧붙인다. 스킬이 임의로
```
`new_string`:
```
  7개 룰 항목은 `[x]`로 체크한다. N/A였던 항목은 옆에 `(해당 변경 없음)`을 덧붙인다. 스킬이 임의로
```

- [ ] **Step 3: 변경 검증**

Run: `grep -nE "6종|6개" settlement-service/.claude/skills/create-github-pr/SKILL.md`
Expected: 아무 줄도 출력되지 않는다.

Run: `grep -nE "7종|7개" settlement-service/.claude/skills/create-github-pr/SKILL.md`
Expected: 2.5단계·4단계 두 줄이 출력된다.

- [ ] **Step 4: 커밋**

```bash
git add settlement-service/.claude/skills/create-github-pr/SKILL.md
git commit -m "docs: create-github-pr 룰 검증 7종 반영"
```

---

## 최종 점검 (전 태스크 완료 후)

- [ ] **전역 6→7 누락 점검**

Run:
```bash
grep -rnE "룰 6종|6개를 병렬|rule-checker 6|6/6|6개 룰" \
  settlement-service/.claude .github/ISSUE_TEMPLATE/pull_request_template.md
```
Expected: 아무 줄도 출력되지 않는다(잔존 "6" 표기 없음).

- [ ] **보안 룰 연결 확인**

Run:
```bash
grep -rn "security.md\|security |보안" settlement-service/.claude/skills/verify-rules/SKILL.md
test -f settlement-service/.claude/rules/security.md && echo "rule file OK"
```
Expected: verify-rules가 `security.md`를 참조하고, 룰 파일이 존재한다.
