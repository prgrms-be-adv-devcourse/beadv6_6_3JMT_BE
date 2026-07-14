---
name: commit-settlement-changes
description: settlement-service 변경사항을 분석하고 정산 서비스 Git 규칙에 맞는 한국어 커밋 메시지를 제안한 뒤 안전하게 stage·commit한다. 사용자가 정산 작업에서 "커밋해줘", "정산 변경 커밋", "커밋 메시지 만들어줘"라고 요청할 때 사용한다. 다른 모듈의 변경이나 PR 생성에는 사용하지 않는다.
---

# 정산 변경 커밋

모든 경로는 `git rev-parse --show-toplevel`로 구한 저장소 루트를 기준으로 해석한다.

1. `git status --short`, staged/unstaged diff를 확인하고 `settlement-service/` 변경만 대상으로 삼는다.
2. `settlement-service/.claude/rules/git-convention.md`를 읽는다.
3. 현재 브랜치가 `main` 또는 `develop`이면 작업 브랜치 여부를 확인한다. 임의로 브랜치를 만들지 않는다.
4. 관련 없는 사용자 변경과 다른 모듈 변경은 stage하지 않는다. `git add -A`를 무조건 사용하지 않는다.
5. diff의 주된 목적에 따라 `feat|fix|refactor|docs|chore|style|test: 한국어 설명` 형식으로 메시지를 정한다.
6. 포함 파일이나 메시지가 애매하면 실행 전에 초안을 확인받는다.
7. 커밋 메시지 본문이나 trailer에 `Co-Authored-By`를 추가하지 않는다. 특히 `Co-Authored-By: Claude`를 절대 붙이지 않는다.
8. `git commit -m`을 실행하고 `<short-hash> (커밋)` 형식으로 해시와 제목을 보고한다. hook을 우회하지 않는다.

push, PR, stash, reset은 별도 요청 없이 하지 않는다.
