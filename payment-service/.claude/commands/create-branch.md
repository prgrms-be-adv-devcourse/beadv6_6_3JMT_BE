---
description: git-conventions 규칙에 맞는 작업 브랜치를 생성합니다. 이슈 라벨로 type을 추론하고 설명을 영어 kebab-case로 변환해 develop에서 분기합니다.
argument-hint: "[이슈번호] [설명] 예) 12 결제 승인 API 구현"
---

사용자가 입력한 내용: **$ARGUMENTS**

아래 절차를 단계별로 수행하라.

## 1단계: 이슈번호·설명 파싱

`$ARGUMENTS`에서 숫자(이슈번호)와 설명 텍스트를 분리한다. `#12`, `12` 형식 모두 허용한다.

이슈번호가 없으면 사용자에게 묻는다. `git-conventions.md`상 이슈번호는 **원칙 필수**이며, 이슈 없는 셋업성 작업만 예외적으로 생략할 수 있다. 사용자가 "이슈 없음/셋업성"임을 명시하면 4단계에서 `type/설명` 형식을 쓴다.

## 2단계: type 결정

이슈번호가 있으면 라벨을 조회해 type을 추론한다.

```bash
gh issue view <번호> --json labels,title
```

- 라벨(`feat`/`fix`/`docs`/`chore`/`refactor`/`test`) 중 하나가 있으면 그 라벨을 type으로 사용.
- 라벨이 없거나 모호하면 입력 설명·이슈 제목 기반으로 추론.
- 그래도 불명확하면 사용자에게 "어떤 type으로 할까요? (feat/fix/docs/chore/refactor/test)" 라고 확인한다.

## 3단계: 설명 → 영어 kebab-case 변환

한국어 설명을 간결한 영어 kebab-case로 변환한다.
- 예: "결제 승인 API 구현" → `payment-confirm-api`
- 예: "환불 실패 버그 수정" → `refund-failure-fix`

소문자, 하이픈 구분, 핵심 명사 위주로 간결하게. 변환 결과는 4단계 초안에서 사용자가 확인한다.

## 4단계: 브랜치명 조립

- 이슈번호 있음: `type/#이슈번호-설명` (예: `feat/#12-payment-confirm-api`)
- 셋업성 예외: `type/설명` (예: `chore/checkstyle`)

## 5단계: 분기 기준 확인

`git-conventions.md`상 작업 브랜치는 `develop`에서 분기한다.

```bash
git branch --show-current
git status --short
```

- 분기 기준은 최신 `develop`(`origin/develop`)을 기본으로 한다.
- **로컬에 미커밋 변경이 있으면 경고만** 하고("커밋되지 않은 변경이 있습니다. 새 브랜치로 가져갈지 확인해 주세요."), 임의로 stash/reset 하지 않는다.

## 6단계: 초안 제시 및 동의 획득

아래 형식으로 초안을 보여준다.

```
=== 브랜치 생성 초안 ===
이슈: #12 (라벨: feat)
브랜치명: feat/#12-payment-confirm-api
분기 기준: origin/develop (fetch 후 최신)
=======================

이 브랜치를 생성할까요? (수정이 필요하면 말씀해 주세요)
```

브랜치명 수정 요청이 있으면 반영 후 다시 보여준다. **명시적 동의를 받기 전까지는 브랜치 생성 명령을 실행하지 않는다.**

## 7단계: 브랜치 생성

동의를 받으면 실행한다.

```bash
git fetch origin develop
git switch -c <브랜치명> origin/develop
```

실행 후 생성된 브랜치명과 현재 위치(`git branch --show-current`)를 보고한다.
