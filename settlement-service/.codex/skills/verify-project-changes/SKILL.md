---
name: verify-project-changes
description: Use when 현재 브랜치나 작업 트리의 전체 변경을 커밋·PR 전에 검토하거나, 여러 서비스·모듈이 함께 바뀐 diff에서 경로별 프로젝트 규칙 누락을 방지해야 할 때.
---

# 전체 프로젝트 변경 검증

검증 전에 전체 변경 집합을 고정하고 모든 변경 경로를 규칙 검사 또는 일반 코드 검토에 배정한다. 검증은 읽기 전용이며 수정, stage, commit, push를 수행하지 않는다.

## 검증 계약

| 항목 | 계약 |
| --- | --- |
| 검증 대상 | `<base>...<head>` 전체 diff와 관련 작업 트리 변경 |
| 규칙 탐색 | 각 변경 경로에 적용되는 `AGENTS.md`, `CLAUDE.md`, `.claude/rules/*.md` |
| 규칙 없음 | 일반 정확성·회귀·테스트·보안 검토 수행 |
| 검증 불가 | `PASS` 금지. 확인하지 못한 항목과 위험 보고 |

사용자가 지정한 base와 head를 우선한다. 지정하지 않았다면 추적 브랜치, 저장소 기본 브랜치, merge-base를 확인해 근거와 함께 결정한다. base를 신뢰성 있게 정할 수 없으면 추측으로 통과시키지 않는다.

## 절차

### 1. 전체 변경 집합 고정

저장소 루트에서 다음 입력을 수집한다.

- base, head, merge-base와 `git log <base>..<head> --format=fuller`로 확인한 각 commit의 hash, 제목, 전체 body, trailer
- `git diff <base>...<head>`의 전체 파일 목록, 상태, diff
- `git status --short`, staged diff, unstaged diff
- 관련 untracked 파일의 경로와 내용

위 입력의 합집합을 coverage manifest로 기록한다. 처음부터 서비스나 확장자로 경로를 제한하지 않는다. 삭제·이름 변경·binary 파일도 manifest에 남기고, 확인 가능한 diff나 이전 내용을 검사한다. 사용자가 일부 변경을 명시적으로 제외했다면 제외 범위와 남는 위험을 결과에 적는다.

### 2. 경로별 규칙 매핑

각 manifest 경로에 대해 저장소 루트부터 해당 파일의 상위 디렉터리까지 적용 가능한 `AGENTS.md`와 `CLAUDE.md`를 찾고 완전히 읽는다. 해당 프로젝트 루트의 `.claude/rules/*.md`와 지침 문서가 참조하는 규칙도 완전히 읽는다.

PR 또는 이슈 초안이 입력에 포함되거나 그 초안 검증을 요청받으면 현재 checkout의 `.github/PULL_REQUEST_TEMPLATE.md`와 관련 `.github/ISSUE_TEMPLATE/` 파일을 완전히 읽고 체크리스트·본문 구조·메타데이터의 단일 진실 공급원으로 사용한다. 필요한 템플릿이 없거나 읽지 못하면 해당 검사를 `UNVERIFIED`로 보고한다.

규칙과 적용 경로의 매핑표를 만든다. 적용 규칙을 찾지 못한 경로도 누락하지 말고 `general-code-review`에 배정한다. 규칙 충돌이나 적용 범위가 불명확하면 그 항목을 검증 불가로 처리한다.

### 3. 독립 검사 수행

독립적인 검사는 병렬 실행할 수 있다. 검사자 하나에는 규칙 하나와 명시적인 범위 하나만 맡기고, 해당 범위의 파일·diff·commit 입력만 제공한다. 모든 검사자는 다음 읽기 전용 계약을 따른다.

- 지정된 규칙을 완전히 읽고 그 규칙에 근거한 위반만 판단한다.
- 파일 목록만 보지 말고 실제 diff와 필요한 전체 파일 문맥을 확인한다.
- 파일을 수정하거나 stage·commit하지 않는다.
- 규칙을 읽지 못했거나 필요한 증거를 확인하지 못하면 `UNVERIFIED`를 반환한다.

규칙 검사와 별도로 전체 manifest를 정확성, 회귀, 테스트 적절성, 보안, 서비스 간 계약 관점에서 검토한다. 규칙이 없는 경로는 이 일반 검토가 필수다.

### 4. 누락 방지와 집계

검사 결과를 coverage manifest와 다시 대조한다. 모든 변경 파일이 하나 이상의 규칙 검사 또는 `general-code-review`에 포함되어야 한다. 예를 들어 `settlement-service/`와 `payment-service/`가 함께 바뀌면 두 경로를 모두 검증해야 한다.

각 결과를 다음 형식으로만 정리한다.

```text
RULE: <규칙 파일 경로 | general-code-review | coverage-completeness>
SCOPE: <검사한 파일·diff·commit 범위>
STATUS: <PASS | FAIL | N/A | UNVERIFIED>
FINDINGS:
- <severity> <파일:라인 또는 대상> — <근거와 영향>
```

위반이 없으면 `FINDINGS`에 `- none`을 적는다. `N/A`는 규칙의 적용 대상이 실제로 없을 때만 사용한다. `UNVERIFIED`에는 확인하지 못한 항목과 그 위험을 적는다.

마지막에 `coverage-completeness` 결과를 추가한다. manifest 누락, `FAIL`, `UNVERIFIED`가 하나라도 있으면 전체 검증을 통과로 보고하지 않는다. 실행하지 않은 테스트나 확인하지 못한 규칙도 통과한 것처럼 쓰지 않는다.
