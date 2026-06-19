# create-github-pr 스킬 설계

## 목적

현재 브랜치의 커밋·diff를 분석해 레포의 PR 템플릿
(`.github/ISSUE_TEMPLATE/pull_request_template.md`)을 채우고, 사용자 승인 후
`gh pr create`로 실제 PR을 생성하는 스킬. (이슈는 `create-github-issue`에서 처리)

## 범위

- 대상: `pull_request_template.md` 한 종류
- 동작: 컨텍스트 수집 → 템플릿 채움 → 초안+base 보여주고 승인 → push → `gh pr create`
- 제외: 이슈 생성, 코드 리뷰, 브랜치 머지 전략
- 설치 위치: 이 레포 전용 `.claude/skills/create-github-pr/`
- 구현 방식: 헬퍼 스크립트 없이 `git` / `gh` CLI만 사용 (A안)

## 스킬 정체성

- 이름: `create-github-pr`
- description 트리거: "PR / pull request를 작성·생성할 때". 대상 명사 **PR**을 박아
  `create-github-issue`, `finishing-a-development-branch`와 구분한다.

## 워크플로우

1. **사전 점검**
   - `gh` 설치·인증 확인 (안 되면 안내 후 중단)
   - 현재 브랜치가 base 후보(`develop`/`main`)가 아닌지 확인
2. **컨텍스트 수집 (git)**
   - 현재 브랜치명
   - base 대비 커밋 목록 (`git log <base>..HEAD`)
   - diff (`git diff <base>...HEAD`) — 변경 파일·내용
   - 테스트 파일 변경 여부
3. **템플릿 읽기 (런타임 동적)**
   - `.github/ISSUE_TEMPLATE/pull_request_template.md`를 읽음
   - 섹션 구조와 체크리스트를 **파일에서 그대로** 가져옴
   - 템플릿이 단일 진실 공급원. 스킬에 섹션/체크리스트 내용을 복사하지 않는다.
   - **체크리스트는 고정값이 아님**: 나중에 컨벤션·검증 rule이 정해지면 템플릿이
     바뀌고, 그러면 스킬은 안 건드려도 자동 반영돼야 한다.
4. **본문 채우기**
   - 설명 / 변경 사항 요약: 커밋·diff 기반으로 작성
   - 테스트 계획: 테스트 파일 변경 여부로 추론해 채움
   - 체크리스트: 템플릿의 각 항목을 diff 근거로 판단
     - 충족 → `[x]` + 근거 한 줄
     - 애매·확인 불가 → `[ ]` (지어내지 않음)
   - 관련 이슈: 브랜치명·커밋에서 `#숫자` 추출, 없으면 placeholder 유지
   - 설계 문서(노션 등): 자동으로 못 찾으면 placeholder 유지
5. **base 브랜치 결정**
   - `feat/*` → `develop` 기본 제안
   - 생성 전 사용자에게 base 확인
6. **승인 게이트**
   - 채운 본문 + base + head 브랜치를 보여주고 승인 대기
   - 승인 전에는 push·생성하지 않음
7. **생성**
   - `git push`로 원격에 브랜치 반영
   - `gh pr create --base <base> --head <branch> --title <title> --body <본문>`
   - 생성된 PR URL 보고

## 톤 가이드 (본문 작성 규칙)

PR 본문은 사람이 직접 쓴 것처럼 담백하게 작성한다. 강제 규칙:

- 이모지 남발·과장된 수식어("획기적인", "강력한") 금지
- 불필요한 친절체·설명 늘리기 금지 → 사실 위주로 짧게
- 정형화된 AI 구조(불릿마다 굵은 글씨 제목, 과한 머리말) 자제
- 슬랙에 적듯 핵심만 담백하게

(템플릿 자체의 섹션 머리말/이모지는 구조이므로 유지하되, **내용**은 위 규칙을 따른다.)

## 엣지 케이스

- `gh` 미설치 / 미인증 → 안내 후 중단
- PR 템플릿 파일 없음 → 사용자에게 알리고 중단
- 현재 브랜치 = base 후보(develop/main) → 경고 후 중단
- base 대비 커밋 없음 → 알리고 중단
- 원격에 동일 head의 PR이 이미 있음 → 알리고 중단(중복 생성 방지)
- 이슈 번호·설계 문서를 못 찾음 → placeholder 유지하고 진행 (되묻지 않음)

## 트리거 경계 (다른 스킬과의 관계)

- **create-github-issue**: 대상 명사 "이슈" vs "PR"로 구분
- `finishing-a-development-branch`: 브랜치 통합 전략(merge/PR/cleanup) **결정**용.
  이 스킬은 결정이 끝난 뒤 "PR 템플릿 채워 생성"하는 실행 단계라 역할이 다름
- `review` / `code-review` / `requesting-code-review`: diff **리뷰**용. "생성"과 무관

## 향후 확장

- 체크리스트 검증 rule이 코드로 정의되면, 템플릿 갱신만으로 반영. 스킬 워크플로우는
  "템플릿을 읽어 항목마다 diff 근거로 판단" 구조라 그대로 유지된다.
