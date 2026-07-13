---
name: create-settlement-issue
description: settlement-service의 버그·기능·개선 요청을 저장소의 실제 GitHub 이슈 템플릿과 라벨에 맞춰 작성하고 사용자 승인 후 등록한다. 사용자가 "정산 이슈 만들어줘", "세틀먼트 버그 이슈 올려줘", "정산 기능 요청 등록"처럼 정산 서비스 GitHub 이슈 생성을 요청할 때 사용한다.
---

# 정산 GitHub 이슈 생성

1. 저장소 루트에서 `gh auth status`와 `.github/ISSUE_TEMPLATE/`을 확인한다.
2. 요청을 버그·기능·기타 작업으로 분류하고 해당 템플릿을 실행 시점에 읽는다.
3. 섹션 구조는 유지하고 안내 문구를 정산 요청의 실제 내용으로 교체한다.
4. 버그는 설명·재현 단계·예상 결과·실제 결과, 기능은 문제·제안 기능을 채운다. 필수 정보가 부족하면 한 번에 묻는다.
5. 과장 수식어, 강조 남발, 불필요한 이모지를 넣지 않고 사람이 쓴 것처럼 담백하게 작성한다.
6. `gh label list`로 저장소에 실제 존재하는 라벨만 사용한다. 템플릿 frontmatter에 `feature`나 `bug`가 있어도 사용하지 않는다.
   - Type `Feature` → 라벨 `feat`
   - Type `Bug` → 라벨 `fix`
   - Type `Task` → 내용에 따라 `docs`, `chore`, `refactor`, `test` 중 선택
   - Task 라벨을 둘 이상으로 해석할 수 있으면 각 선택지의 의미를 설명하고 사용자에게 묻는다.
   - 매핑한 라벨이 저장소에 없으면 새로 만들지 말고 사용자에게 알린다.
7. 기본 필드는 다음과 같이 설정한다.
   - assignee: `@me`
   - Type: 요청 성격에 따라 `Feature`, `Task`, `Bug`
   - GitHub Project: `#45 (프로젝트)`
   - Project Status: `Todo`
   - Priority와 Effort: 설정하지 않음
8. Type, Project, Status의 ID와 옵션은 실행 시점에 `gh api`로 조회한다. 필드가 없거나 권한이 부족하면 추측하지 말고 생성 전 사용자에게 영향을 설명한다.
9. 제목·본문 전체·라벨·assignee·Type·Project·Status 초안을 보여주고 명시적 승인을 받는다.
10. 승인 후 `gh issue create --body-file`로 생성하고 생성된 번호를 `#<번호> (이슈)` 형식으로 보고한다.

다른 서비스 이슈는 만들지 않는다. 승인 후 필드 설정만 실패하면 생성된 이슈를 삭제하지 않고 부분 성공과 누락 필드를 알린다.
