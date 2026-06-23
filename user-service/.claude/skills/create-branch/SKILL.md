---
name: create-branch
description: >-
  Git 컨벤션(<타입>/#<이슈번호>-<내용>)에 맞는 작업 브랜치를 develop 기준으로
  자동 생성합니다. 사용자가 "브랜치 만들어줘", "새 작업 브랜치 생성해줘",
  "이슈 브랜치 파줘", "feat 브랜치 만들어줘"라고 하거나, 새 작업을 시작하기 위해
  브랜치가 필요한 상황이면 사용하세요. 사용자가 "git"이나 "gh"를 직접 언급하지
  않아도 브랜치를 새로 만드는 상황이면 이 스킬을 쓰세요.
  (PR 생성은 create-github-pr, 이슈 생성은 create-github-issue에서 처리합니다.)
---

# create-branch

새 작업 브랜치를 Git 컨벤션에 맞게 자동 생성한다.

## 핵심 원칙
- 브랜치는 항상 develop 최신 상태 기준으로 딴다
- 브랜치명 형식·타입 목록은 `@.claude/rules/git-convention.md`를 따른다
- main, develop에 직접 작업 절대 금지
- 사용자 확인 없이 remote에 push하지 않는다

---

## 실행 순서

### 1. 사전 점검

```bash
# gh 설치·인증 확인
gh auth status
```
- 실패하면 멈추고 `gh auth login` 안내 후 종료

```bash
# origin 원격 저장소 확인
git remote -v
```
- `origin`이 없으면 멈추고 "git remote add origin <URL>" 안내 후 종료

```bash
# uncommitted changes 확인
git status --porcelain
```
- 변경사항이 있으면 멈추지 말고 사용자에게 선택지 제시:
  ```
  uncommitted changes가 있습니다.
  a) git stash로 임시 저장 후 계속 진행
  b) 작업 취소
  ```
  - a 선택 시: `git stash` 실행 → 이후 단계 진행 → 완료 후 `git stash pop` 안내
  - b 선택 시: 종료

```bash
# 현재 브랜치 확인
git rev-parse --abbrev-ref HEAD
```

---

### 2. develop 최신화

```bash
git fetch origin develop
```

현재 브랜치가 `develop`이 아닌 경우에만 checkout 실행:
```bash
git checkout develop
```

```bash
# fast-forward만 허용 (merge commit 방지)
git merge --ff-only origin/develop
```
- fast-forward 실패 시(diverged): 멈추고 "develop 브랜치에 충돌이 있습니다. 수동으로 해결 후 다시 실행해주세요." 안내

---

### 3. GitHub 이슈 목록 조회

```bash
# 내 이슈 우선 조회
gh issue list --state open --limit 20 --assignee @me --json number,title,labels
```

결과를 보기 좋게 출력:
```
[내 이슈]
#12  feat: 상품 목록 조회 API 구현  [feature]
#13  fix: 장바구니 총 금액 계산 오류  [bug]

전체 이슈를 보려면 'all'을 입력하세요.
```

내 이슈가 없거나 'all' 입력 시:
```bash
gh issue list --state open --limit 20 --json number,title,labels
```

이슈가 전혀 없으면 멈추고 안내:
```
GitHub에 열린 이슈가 없습니다.
이슈를 먼저 생성하려면 /create-github-issue 스킬을 사용하세요.
```

---

### 4. 사용자 입력 받기

아래 세 가지를 사용자에게 확인한다.

**이슈 번호 선택**:
- 3단계 목록 중 하나 선택
- 이슈의 label(`bug` → `fix`, `feature` → `feat`)을 보고 브랜치 타입 자동 제안

**브랜치 타입 선택** (자동 제안값 수정 가능):
- 허용 타입은 `@.claude/rules/git-convention.md` 의 브랜치 타입 목록을 따른다

**브랜치 내용 입력**:
- 이슈 제목을 기반으로 slug 자동 제안
- slug 변환 규칙: 공백→`-`, 영문 소문자 유지, 한글 허용, 특수문자(`:`, `/`, `(`, `)`, `[`, `]`) 제거, 30자 이하 권장
  - 예: "상품 목록 조회 API 구현" → `상품-목록-조회-API-구현`
  - 예: "feat: 장바구니 총 금액 계산 오류 fix" → `장바구니-총-금액-계산-오류-fix`
- 사용자가 수정 가능

---

### 5. 브랜치명 생성 및 검증

입력값으로 브랜치명 조합:
```
<타입>/#<이슈번호>-<내용>
예: feat/#12-상품-목록-조회
```

검증 규칙:
- 타입은 허용 목록 안에 있어야 함
- 이슈 번호는 숫자여야 함
- 내용은 공백 없이 kebab-case (한글 허용)
- 형식이 맞지 않으면 재입력 요청

이미 존재하는 브랜치인지 확인:
```bash
git branch --list "<브랜치명>"
```
- 존재하면: "이미 같은 이름의 브랜치가 있습니다. 다른 이름을 입력해주세요." → 4단계로 돌아감

---

### 6. 브랜치 생성

```bash
# 브랜치명을 반드시 따옴표로 감싸 # 이스케이프 처리
git checkout -b "<브랜치명>"
```

생성 완료 후 출력:
```
✅ 브랜치 생성 완료: feat/#12-상품-목록-조회
현재 위치: feat/#12-상품-목록-조회
```

---

### 7. remote push 여부 확인

```
원격 저장소에 브랜치를 올릴까요?
a) 예 — git push -u origin으로 tracking branch 설정
b) 아니오 — 로컬에만 유지
```

- a 선택 시:
  ```bash
  git push -u origin "<브랜치명>"
  ```
  완료 후: "✅ origin에 push 완료. 이제 작업을 시작하세요."

- b 선택 시: "✅ 로컬 브랜치만 생성했습니다. 이제 작업을 시작하세요."

stash를 저장했다면 마지막에 안내:
```
📌 stash 복원이 필요하면: git stash pop
```

---

## 허용 브랜치 타입 목록

허용 타입 및 금지 브랜치(main, develop)는 `@.claude/rules/git-convention.md` 를 따른다.

---

## 엣지 케이스

| 상황 | 처리 |
|------|------|
| gh 미설치 | 멈추고 설치 안내 (`brew install gh`) |
| gh 인증 안 됨 | 멈추고 `gh auth login` 안내 |
| origin 없음 | 멈추고 `git remote add origin <URL>` 안내 |
| uncommitted changes | stash 제안 → 계속 or 취소 |
| 이미 develop에 있음 | checkout 스킵, fetch+merge만 실행 |
| develop fast-forward 실패 | 멈추고 수동 해결 요청 |
| 이슈 없음 | `/create-github-issue` 스킬 연계 안내 후 종료 |
| 브랜치 이미 존재 | 경고 후 다른 이름 입력 요청 |
| 타입 입력 오류 | 허용 목록 안내 후 재입력 요청 |
| push 실패 | 에러 메시지 출력, 로컬 브랜치는 유지됨을 안내 |

---

## 다른 스킬과의 경계

| 상황 | 사용할 스킬 |
|------|------------|
| 이슈가 없어서 먼저 만들어야 함 | `create-github-issue` |
| 브랜치 작업 완료 후 PR 올리기 | `create-github-pr` |
| 이 스킬 | 브랜치 신규 생성만 담당 |
