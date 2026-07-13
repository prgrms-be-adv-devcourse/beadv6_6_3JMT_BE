---
name: create-settlement-issue
description: Use when a user explicitly asks to create a settlement bug, feature, improvement, or maintenance GitHub issue while preserving the existing create-settlement-issue entry point.
---

# 정산 GitHub 이슈 생성 호환 진입점

이 이름으로 들어온 기존 정산 이슈 요청을 저장소 전체 이슈 워크플로에 연결한다.

**REQUIRED SUB-SKILL:** `create-project-issue`를 완전히 읽고 그대로 따른다.

1. 요청의 정산 맥락을 Type 분류, 템플릿 본문과 영향 설명에 전달한다.
2. `create-project-issue`가 실행 시점의 템플릿, 실제 라벨·assignee·Type·Project `#45 (프로젝트)`·Status `Todo`를 조회하게 한다.
3. 요청 경로나 변경 대상을 `settlement-service/`로 제한하지 않는다. 다른 서비스나 공통 영역 요청도 같은 범용 계약으로 처리한다.
4. 제목, 본문 전체와 모든 메타데이터를 공개한 명시적 승인, 승인 후 생성·필드 적용, 사후 검증과 부분 성공 보고는 모두 `create-project-issue`의 계약을 사용한다.

이 스킬은 호환용 이름만 제공한다. 범용 워크플로를 복제하거나 더 좁은 생성 조건을 추가하지 않는다.
