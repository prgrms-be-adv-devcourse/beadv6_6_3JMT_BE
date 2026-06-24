---
name: product-create-issue
description: Repository issue template과 Product Service 규칙을 기준으로 GitHub issue를 생성한다.
---

# Product Issue 생성 Skill

Product Service issue 생성을 요청받으면 이 절차를 따른다.

## 사용자 확인 규칙

사용자가 `이슈 만들어줘`처럼 범위 없이 요청하면 바로 생성하지 않는다.
먼저 아래 정보를 확인한다.

- 이슈 타입: `Feature`, `Bug`, `Task`
- 제목
- 작업 범위
- 관련 API 또는 파일
- 완료 조건
- 테스트 기준

정보를 바탕으로 이슈 초안을 작성한 뒤 사용자에게 보여준다.
사용자가 승인한 뒤에만 GitHub issue를 생성한다.

## 절차

1. `.github/ISSUE_TEMPLATE/feature_request.md`를 읽는다.
2. `product-service/CLAUDE.md`를 읽는다.
3. `product-service/.claude/rules/product-api.md`를 읽는다.
4. 사용자가 준 정보로 이슈 타입과 범위를 정한다. 하나의 이슈는 리뷰 가능한 크기로 유지한다.
5. Product API path, 기준 docs, 테스트 기대값을 포함해 본문을 작성한다.
6. repository에 실제 존재하는 label만 사용한다. 기능 작업은 `feat`, 문서 작업은 `docs`를 사용한다.
7. 최종 title/body/type/label/assignee를 사용자에게 보여주고 승인받은 뒤 GitHub CLI로 issue를 생성한다.

## 명령어 형태

```bash
gh issue create \
  --title "[FEATURE] Product 공개 조회 API 구현" \
  --label "feat" \
  --body-file <body-file>
```

Windows에서 `gh`가 PATH에 없으면 전체 경로를 사용한다.

```powershell
& "C:\Program Files\GitHub CLI\gh.exe" issue create `
  --title "[FEATURE] Product 공개 조회 API 구현" `
  --label "feat" `
  --body-file <body-file>
```

## 본문 규칙

- template placeholder를 그대로 남기지 않는다.
- 구현이 완료됐다고 쓰지 않는다.
- 인증 필요 여부를 명시한다.
- 기준 API spec과 ERD docs를 명시한다.
- 통과해야 하는 테스트를 구체적으로 적는다.
