---
name: verify-rules
description: >-
  현재 브랜치의 변경 코드를 프로젝트 룰 7종(clean-architecture, domain-model,
  controller-exception, code-style, swagger, git-convention, security) 기준으로 rule-checker
  서브에이전트 7개를 병렬 디스패치해 검증합니다. 위반이 하나라도 있으면 게이트로 막고 위반 목록을
  보여줍니다. 사용자가 "룰 검증해줘", "컨벤션 어긴 데 없는지 봐줘", "PR 전에 룰 체크"라고 하거나,
  create-github-pr 스킬이 PR 본문을 채우기 직전에 호출합니다. 코드를 수정하지는 않습니다.
---

# 룰 검증 게이트 (verify-rules)

변경 코드가 프로젝트 룰을 지키는지 검증한다. 룰당 `rule-checker` 서브에이전트 하나씩 7개를
**병렬로** 디스패치하고, 결과를 모아 게이트를 판정한다. **위반이 하나라도 있으면 통과시키지 않는다.**
이 스킬은 검증만 한다 — 코드를 고치지 않고, PR을 만들지도 않는다.

## 룰 → 체크박스 매핑

PR 템플릿(`.github/ISSUE_TEMPLATE/pull_request_template.md`) 체크리스트 7항목과 1:1로 대응한다.
라벨은 템플릿을 단일 진실 공급원으로 보고, 검증 시점에 템플릿에서 읽어 맞춘다.

| RULE_NAME | RULE_FILE | 입력 | 체크박스(템플릿 라벨 앞부분) |
| --- | --- | --- | --- |
| clean-architecture | `settlement-service/.claude/rules/clean-architecture.md` | 변경 파일 | 클린아키텍처 |
| domain-model | `settlement-service/.claude/rules/domain-model.md` | 변경 파일 | 도메인 모델 |
| controller-exception | `settlement-service/.claude/rules/controller-exception.md` | 변경 파일 | Controller/예외 |
| code-style | `settlement-service/.claude/rules/code-style.md` | 변경 파일 | 코드 스타일 |
| swagger | `settlement-service/.claude/rules/swagger.md` | 변경 파일 | Swagger |
| git-convention | `settlement-service/.claude/rules/git-convention.md` | 브랜치명 + 커밋 메시지 | Git 컨벤션 |
| security | `settlement-service/.claude/rules/security.md` | 변경 파일 + diff(추가 라인) + .gitignore | 보안 |

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

### 2. rule-checker 7개 병렬 디스패치

**한 메시지에 Task 7개를 동시에** 띄운다(`subagent_type: rule-checker`). 각 프롬프트에 아래를 채운다.

- 5개(clean-architecture, domain-model, controller-exception, code-style, swagger):
  `RULE_NAME`, `RULE_FILE`, `INPUT = CHANGED_FILES`(1단계 변경 파일 목록).
- git-convention 1개: `RULE_NAME=git-convention`, `RULE_FILE`, `INPUT = 브랜치명 + 커밋 제목 목록`.
- security 1개: `RULE_NAME=security`, `RULE_FILE`, `INPUT = CHANGED_FILES`. 프롬프트에 "diff 추가 라인과
  `.gitignore`도 직접 확인하라(룰 §0 검사 방법을 따른다)"를 명시한다. base 는 1단계 기준과 동일.

디스패치 프롬프트 틀:

```
RULE_NAME: clean-architecture
RULE_FILE: settlement-service/.claude/rules/clean-architecture.md
INPUT (CHANGED_FILES):
- src/main/java/.../Foo.java
- src/main/java/.../Bar.java

위 룰 파일 하나만 기준으로 변경 파일의 위반을 검사하고, 지정된 출력 포맷으로만 답하라.
```

```
RULE_NAME: git-convention
RULE_FILE: settlement-service/.claude/rules/git-convention.md
INPUT (BRANCH + COMMITS):
- branch: feat/#14-settlement-batch-execution
- commits:
  - feat: ...
  - fix: ...

위 룰 파일 하나만 기준으로 브랜치명·커밋 메시지의 위반을 검사하고, 지정된 출력 포맷으로만 답하라.
```

### 3. 집계 + 게이트 판정

각 rule-checker가 돌려준 `RULE / STATUS / VIOLATIONS` 리포트를 모은다.

- 7개 모두 `PASS` 또는 `N/A` → **게이트 통과.**
- 하나라도 `FAIL` → **게이트 STOP.**
- rule-checker가 RULE_FILE을 읽지 못했거나 리포트 포맷을 못 지켰으면, 그 룰은 PASS로 보지 않고 **게이트 STOP**으로 처리한다. (룰을 못 읽고 조용히 통과하는 사고 방지)

### 4. 결과 출력

**통과 시** — 체크리스트를 렌더해 보여준다(아래는 `create-github-pr`가 본문에 그대로 쓸 수 있다).

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
- **code-review / review**: 코드 품질 전반을 리뷰하는 스킬이다. 이 스킬은 **프로젝트 룰 7종 위반만**
  본다. 범위가 다르다.
- 이 스킬은 코드를 수정하지 않는다(검증·게이트·리포트까지만). 수정은 사용자가 한다.
