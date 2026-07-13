---
name: create-settlement-pr
description: Use when a user explicitly asks to create or update a settlement pull request while preserving the existing create-settlement-pr entry point.
---

# 정산 GitHub PR 생성 호환 진입점

이 이름으로 들어온 기존 정산 PR 요청을 범용 PR 워크플로에 연결한다.

**REQUIRED SUB-SKILL:** `create-project-pr`를 완전히 읽고 그대로 따른다.

1. 요청의 정산 맥락을 PR 설명과 영향 범위 분석에 전달한다.
2. `create-project-pr`가 `verify-project-changes`로 base 대비 전체 diff와 관련 작업 트리를 검증하게 한다.
3. 변경 파일을 `settlement-service/`로 제한하거나 정산 경로 존재 여부를 별도 게이트로 검사하지 않는다.
4. 정산 변경에는 발견된 정산 규칙과 테스트를 적용하되, 다른 모듈과 공통 영역도 각 경로의 규칙 또는 일반 검토와 영향 범위 테스트에 포함한다.
5. 템플릿 초안, 메타데이터, 승인 경계, push, 새 PR 생성 또는 기존 PR 갱신, 사후 검증은 모두 `create-project-pr`의 계약을 사용한다.

이 스킬은 호환용 이름만 제공한다. 범용 워크플로를 복제하거나 더 좁은 검증·생성 조건을 추가하지 않는다.
