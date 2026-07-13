---
name: create-settlement-pr
description: settlement-service 작업 브랜치의 commit·diff·테스트·정산 규칙을 검증하고 저장소 PR 템플릿으로 초안을 작성한 뒤 사용자 승인 후 push와 PR 생성을 수행한다. 사용자가 "정산 PR 만들어줘", "세틀먼트 풀리퀘 올려줘", "이 정산 브랜치 PR 생성"이라고 요청할 때 사용한다.
---

# 정산 GitHub PR 생성

정산 PR 요청에서는 범용 `github:yeet`보다 이 스킬의 프로젝트 기본값을 우선한다.

1. 저장소 루트에서 `gh auth status`, 현재 브랜치, 작업 트리를 확인한다. base 브랜치, 미커밋 변경, base 대비 커밋 없음이면 중단한다.
2. `git fetch origin` 후 기본 base인 `develop` 대비 commit·stat·diff·뒤처진 commit을 수집한다.
3. 변경 파일이 `settlement-service/` 범위인지 확인하고 `verify-settlement-rules`를 적용한다.
4. 영향에 맞는 정산 테스트를 실행하고 마지막에 `./gradlew :settlement-service:build`를 검토한다. 실행하지 못한 검증을 통과로 쓰지 않는다.
5. `.github/PULL_REQUEST_TEMPLATE.md`를 읽고 현재 섹션과 체크리스트 구조를 유지한다.
6. 본문은 과장 수식어, 강조 남발, 불필요한 이모지 없이 담백하게 작성한다. 확인된 항목만 `[x]`로 표시하고 관련 이슈를 브랜치와 commit에서 찾는다. 번호는 `#<번호> (이슈)`처럼 유형을 붙인다.
   - 저장소 파일 링크는 상대경로가 아니라 `https://github.com/<owner>/<repo>/blob/<head-sha>/<path>` 형식의 전체 URL을 사용한다.
   - 최종 push 후의 HEAD SHA를 사용하고, 각 링크는 `gh api "repos/<owner>/<repo>/contents/<path>?ref=<head-sha>"`로 존재 여부를 확인한다.
7. PR 기본값을 적용한다.
   - 상태: 기본은 Draft가 아닌 **Ready for review**다. 사용자가 명시적으로 Draft를 요청한 경우에만 `--draft`를 사용한다.
   - assignee: `@me`
   - label: 브랜치 타입을 우선 사용하고, 브랜치에서 타입을 확인할 수 없을 때만 대표 commit 타입을 사용한다. `gh label list`로 존재 여부를 확인한다.
   - reviewer: `git-mesome`, `Jinpyo-An`, `oxix97`, `gfkmkl` 전원을 기본 지정한다.
   - reviewer 목록에서는 현재 GitHub 로그인 사용자만 제외한다.
8. `gh pr list --head`로 중복 PR을 확인한다.
9. base/head, 제목, 라벨, assignee, reviewer, 실제 본문 전체를 보여주고 명시적 승인을 받는다.
10. 승인 후에만 push를 실행하고 `git rev-parse HEAD`로 링크에 사용할 SHA를 확정한 다음 `gh pr create --body-file`을 실행한다. 기본 생성 명령에는 `--draft`를 넣지 않는다.
11. 생성 직후 `gh pr view --json body,isDraft,labels,assignees,reviewRequests`로 본문 링크, 상태, 메타데이터를 검증한다. 누락된 기본값은 보고만 하지 말고 `gh pr ready` 또는 `gh pr edit`로 적용한 뒤 다시 검증한다.
12. 생성 결과는 `#<번호> (PR)` 형식과 URL로 보고한다.
