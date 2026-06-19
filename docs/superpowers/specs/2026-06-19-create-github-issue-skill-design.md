# create-github-issue 스킬 설계

## 목적

사용자가 자연어로 설명한 내용을 레포의 GitHub 이슈 템플릿(`.github/ISSUE_TEMPLATE/`)에
맞춰 채우고, `gh issue create`로 실제 이슈를 생성하는 스킬. 버그 리포트와 기능 요청
두 종류의 이슈를 다룬다. (PR은 별도 스킬에서 처리)

## 범위

- 대상: `bug_report.md`, `feature_request.md` 두 이슈 템플릿
- 제외: PR 템플릿(`pull_request_template.md`)은 별도 PR 생성 스킬에서 다룸
- 설치 위치: 이 레포 전용 `.claude/skills/create-github-issue/`

## 스킬 정체성

- 이름: `create-github-issue`
- description 트리거: "GitHub 이슈(버그·기능)를 작성·등록할 때". 대상 명사 **이슈**를
  명확히 박아 PR 생성 스킬과 구분한다.

## 워크플로우

1. **템플릿 종류 결정**
   - 사용자가 명시("버그 이슈 만들어", "기능 요청 올려줘")하면 그대로 사용
   - 명시가 없으면 자연어 내용으로 bug / feature 판별
   - 애매하면 사용자에게 한 번 확인
2. **템플릿 읽기 (런타임 동적)**
   - `.github/ISSUE_TEMPLATE/`에서 해당 템플릿 파일을 읽음
   - frontmatter(`--- ... ---`)와 본문을 분리
   - frontmatter 파싱:
     - `title`의 접두사(예: `[BUG]`, `[FEATURE]`)만 사용. 뒤 문구
       ("간단한 버그 설명을 작성하세요." 등)는 플레이스홀더이므로 실제 제목으로 교체
     - `labels`(예: `bug`, `feature`) 추출
     - `assignees`: 빈 값이면 미할당
   - 본문 섹션 구조를 파싱
   - 템플릿이 단일 진실 공급원. 스킬에 템플릿 내용을 복사하지 않는다.

   **중요**: 이 frontmatter는 GitHub 웹 UI에서만 자동 해석된다. `gh issue create`
   CLI는 frontmatter를 해석하지 않으므로, 스킬이 직접 파싱해 CLI 플래그
   (`--title`, `--label`, `--assignee`)로 변환해야 한다.
3. **본문 채우기**
   - 사용자가 준 정보로 각 섹션을 채움
   - 템플릿의 안내 문구(예: "어떤 버그가 발생했는지...")는 실제 내용으로 대체
4. **필수 항목 보강**
   - 버그 필수: 버그 설명 / 재현 단계 / 예상 결과 / 실제 결과
   - 기능 필수: 문제 제기 / 제안하는 기능
   - 필수 항목이 비면 **부족한 것만 모아 한 번에** 사용자에게 되물음
   - 선택 항목(환경, 스크린샷, 추가 정보 등)은 비어도 되묻지 않음
5. **생성**
   - `gh issue create --title "<접두사> <제목>" --label <라벨> --body <본문>` 실행
   - `--body`에는 **frontmatter를 제외한 본문 섹션만** 넣는다 (frontmatter가 본문에
     그대로 들어가면 안 됨)
   - 생성된 이슈 URL을 사용자에게 보고

## 톤 가이드 (본문 작성 규칙)

이슈 본문은 사람이 직접 쓴 것처럼 담백하게 작성한다. 강제 규칙:

- 이모지 남발·과장된 수식어("획기적인", "강력한") 금지
- 불필요한 친절체·설명 늘리기 금지 → 사실 위주로 짧게
- 정형화된 AI 구조(불릿마다 굵은 글씨 제목, 과한 머리말) 자제
- 슬랙에 적듯 핵심만 담백하게

(템플릿 자체의 섹션 머리말/이모지는 템플릿 구조이므로 유지하되, **내용**은 위 규칙을 따른다.)

## 엣지 케이스

- `gh` 미설치 / 미인증 → 안내 메시지 출력 후 중단
- `.github/ISSUE_TEMPLATE/` 또는 템플릿 파일 없음 → 사용자에게 알리고 중단
- 환경(OS/버전) 등 선택 항목은 비워도 진행

## 트리거 경계 (다른 스킬과의 관계)

- **PR 생성 스킬(예정)**: 대상 명사 "PR / pull request"로 구분. 충돌 없음
- `review`, `code-review`, `requesting-code-review`: PR/diff **리뷰**용. "생성"과 무관
- `finishing-a-development-branch`: 브랜치 통합(merge/PR/cleanup) 전략 결정. 이슈 생성과
  무관. (단 향후 PR 생성 스킬은 이쪽과 경계를 명확히 해야 함)

## 향후 확장

- PR 생성 스킬을 별도로 만들 때 description을 "PR 템플릿을 채워 `gh pr create`로 생성"
  으로 좁게 한정해 `finishing-a-development-branch`와 구분한다.
