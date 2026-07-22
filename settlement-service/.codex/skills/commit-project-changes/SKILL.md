---
name: commit-project-changes
description: 현재 저장소의 담당 범위(`settlement-service/`, `user-service/`, `admin-service`의 `admin.settlement` main/test) 변경을 분석하고 경로별 Git 규칙에 맞는 한국어 커밋 메시지를 제안한 뒤 안전하게 stage·commit한다. 사용자가 담당 범위 작업에서 "커밋해줘", "변경 커밋", "커밋 메시지 만들어줘"라고 요청할 때 사용한다. 담당 범위 밖 변경이나 PR 생성에는 사용하지 않는다.
---

# 담당 범위 변경 커밋

모든 경로는 `git rev-parse --show-toplevel`로 구한 저장소 루트를 기준으로 해석한다.

담당 범위는 다음 경로로 제한한다.

- `settlement-service/` 전체
- `user-service/` 전체
- `admin-service/src/main/java/com/prompthub/admin/settlement/`
- `admin-service/src/test/java/com/prompthub/admin/settlement/`

1. `git status --short`, staged/unstaged diff와 관련 untracked 파일을 확인하고 사용자 요청에 해당하는 정확한 변경 집합을 정한다. 해당 변경이 없으면 메시지 작성·stage·commit 없이 그 사실을 보고하고 종료한다.
2. `settlement-service/AGENTS.md`와 대상 경로에 적용되는 `AGENTS.md`, `CLAUDE.md`, 참조된 Git 컨벤션을 완전히 읽는다. 모듈별 Git 규칙이 없으면 `docs/guides/git-convention.md`를 따른다. 적용 규칙이 충돌하면 임의로 선택하지 않고 사용자에게 확인한다.
3. 현재 브랜치가 `main` 또는 `develop`이면 작업 브랜치 여부를 확인한다. 현재 작업 브랜치의 이름·연결 이슈와 요청 변경의 관련성이 불분명해도 커밋 전에 확인한다. 임의로 브랜치를 만들지 않는다.
4. 담당 범위 밖 변경, 요청과 관련 없는 사용자 변경은 stage하지 않는다. 파일 경로를 명시해 stage하며 `git add -A`를 무조건 사용하지 않는다.
5. 커밋 직전에 staged 파일 목록과 diff 내용을 다시 확인해 확정한 변경 집합과 일치하는지 검증한다. 요청과 무관한 파일이나 hunk가 이미 staged 상태라면 임의로 unstage하거나 부분 stage하지 않고 커밋을 중단한 뒤 포함 또는 분리 방법을 사용자에게 확인한다.
6. 적용되는 Git 컨벤션과 diff의 주된 목적에 따라 `<type>: <한국어 설명>` 형식으로 메시지를 정한다.
7. 서로 다른 목적의 변경이 섞였으면 커밋 분리를 제안한다. 여러 모듈을 함께 커밋하거나 포함 파일·메시지가 애매하면 실행 전에 파일 목록과 메시지 초안을 확인받는다.
8. 커밋 메시지 본문이나 trailer에 `Co-Authored-By`를 추가하지 않는다. 특히 `Co-Authored-By: Claude`를 절대 붙이지 않는다.
9. `git commit -m`을 실행하고 `<short-hash> (커밋)` 형식으로 해시와 제목을 보고한다. hook을 우회하지 않는다.

push, PR, stash, reset은 별도 요청 없이 하지 않는다.
