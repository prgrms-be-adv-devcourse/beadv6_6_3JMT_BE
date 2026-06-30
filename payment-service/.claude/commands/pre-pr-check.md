---
description: PR 작성 전 origin fetch → diverge/충돌 가능성을 확인합니다. /create-pr 실행 시 자동으로 선행됩니다.
---

아래 절차를 단계별로 수행하라.

## 1단계: origin fetch

```bash
git fetch origin
```

## 2단계: 현재 브랜치 확인 및 미커밋 변경 체크

```bash
git branch --show-current
git status --short
```

`git status --short` 출력이 있으면 "⚠️ 미커밋 변경이 있습니다. stash 또는 커밋 후 진행을 권장합니다."라고 경고한다.

## 3단계: diverge 현황 파악

```bash
git log origin/develop..HEAD --oneline
git log HEAD..origin/develop --oneline
```

- 첫 번째 명령 결과: PR에 포함될 내 커밋 목록과 개수
- 두 번째 명령 결과: 내 브랜치 분기 이후 develop에 새로 들어온 커밋 목록과 개수

## 4단계: 파일 충돌 가능성 확인 (비파괴적)

```bash
git diff --name-only HEAD...origin/develop
git diff --name-only origin/develop...HEAD
```

두 결과에서 공통으로 등장하는 파일 중 `payment-service/` 경로 하위 파일을 찾는다.

- 겹치는 파일이 있으면 "⚠️ 충돌 가능성 있는 파일: <목록>" 경고
- 겹치는 파일이 없으면 "✅ 충돌 없음"

## 5단계: 결과 요약 출력

아래 형식으로 요약한다.

```
=== PR 사전 검증 결과 ===
브랜치     : <현재 브랜치>
미커밋 변경 : 없음 / 있음 (N개)
내 커밋 수  : N개 (PR에 포함)
develop 신규: N개
충돌 가능성 : 없음 / ⚠️ <파일목록>
========================
```

- 충돌 가능 파일이 있으면 "rebase 또는 merge 후 PR을 생성하는 것을 권장합니다."
- 모두 이상 없으면 "PR 생성을 진행해도 좋습니다."
