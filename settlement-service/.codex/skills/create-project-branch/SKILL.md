---
name: create-project-branch
description: 현재 저장소의 담당 범위(`settlement-service/`, `user-service/`, `admin-service`의 `admin.settlement` main/test)에 속한 GitHub 이슈 또는 작업 설명을 바탕으로 경로별 Git 규칙에 맞는 작업 브랜치를 최신 develop에서 생성한다. 사용자가 담당 범위 작업에서 "브랜치 만들어줘", "이슈 브랜치 파줘", "#12로 브랜치 생성"이라고 요청할 때 사용한다. 담당 범위 밖 작업에는 사용하지 않는다.
---

# 담당 범위 작업 브랜치 생성

1. 저장소 루트와 `git status --short`, 현재 브랜치, origin을 확인한다.
2. 작업 대상이 `settlement-service/`, `user-service/`, `admin-service`의 `admin.settlement` main/test 중 하나에 속하는지 확인한다. 담당 범위 밖이면 브랜치를 만들지 않고 범위를 알린다.
3. `settlement-service/AGENTS.md`와 대상 경로에 적용되는 `AGENTS.md`, `CLAUDE.md`, 참조된 Git 컨벤션을 완전히 읽는다. 모듈별 Git 규칙이 없으면 `docs/guides/git-convention.md`를 따른다. 여러 경로의 규칙이 충돌하면 임의로 선택하지 않고 사용자에게 확인한다.
4. 미커밋 변경이 있으면 새 브랜치에 가져갈지 확인한다. 임의 stash/reset/commit을 하지 않는다.
5. 이슈 번호가 있으면 `gh issue view <번호> --json title,labels`로 실재 여부와 타입을 확인한다.
6. 규칙이 달리 정하지 않는 한 `<type>/#<issue>-<kebab-case>`로 제안한다. 내용의 언어가 정해져 있지 않으면 이슈 제목과 저장소의 기존 브랜치 사용례를 근거로 정하고, 근거가 둘 이상이면 사용자에게 확인한다.
7. `git fetch origin develop` 후 `origin/develop`에서 `git switch -c "<branch>" origin/develop`을 실행한다. 같은 이름을 덮어쓰지 않는다.
8. 생성 결과는 `<branch> (브랜치)`, `<short-hash> (커밋)` 형식으로 보고한다. push는 함께 요청받았을 때만 한다.
