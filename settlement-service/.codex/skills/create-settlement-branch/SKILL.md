---
name: create-settlement-branch
description: settlement-service GitHub 이슈 또는 정산 작업 설명을 바탕으로 Git 컨벤션에 맞는 작업 브랜치를 최신 develop에서 생성한다. 사용자가 정산 작업에서 "브랜치 만들어줘", "정산 이슈 브랜치 파줘", "#12로 브랜치 생성"이라고 요청할 때 사용한다. 다른 서비스 브랜치에는 사용하지 않는다.
---

# 정산 브랜치 생성

1. 저장소 루트와 `git status --short`, 현재 브랜치, origin을 확인한다.
2. `settlement-service/.claude/rules/git-convention.md`를 읽는다.
3. 미커밋 변경이 있으면 새 브랜치에 가져갈지 확인한다. 임의 stash/reset/commit을 하지 않는다.
4. 이슈 번호가 있으면 `gh issue view <번호> --json title,labels`로 실재 여부와 타입을 확인한다.
5. 규칙이 달리 정하지 않는 한 `<type>/#<issue>-<english-kebab-case>`로 제안한다.
6. `git fetch origin develop` 후 `origin/develop`에서 `git switch -c "<branch>" origin/develop`을 실행한다. 같은 이름을 덮어쓰지 않는다.
7. 생성 결과는 `<branch> (브랜치)`, `<short-hash> (커밋)` 형식으로 보고한다. push는 함께 요청받았을 때만 한다.
