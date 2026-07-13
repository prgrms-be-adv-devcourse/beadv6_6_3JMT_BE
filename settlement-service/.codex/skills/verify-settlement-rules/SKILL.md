---
name: verify-settlement-rules
description: settlement-service diff를 기존 CLAUDE.md와 .claude/rules의 아키텍처·도메인·Controller·스타일·Swagger·Kafka·보안·Git 규칙에 대조한다. 정산 코드 리뷰, "정산 룰 검증", "세틀먼트 컨벤션 확인", 정산 PR 전 규칙 체크 요청에 사용한다. 검증만 하며 별도 수정 요청이 없으면 코드를 바꾸지 않는다.
---

# 정산 서비스 규칙 검증

1. 저장소 루트 기준으로 현재 diff, 변경 파일, commit 제목을 수집하고 `settlement-service/` 변경만 검증한다.
2. `settlement-service/CLAUDE.md`와 다음 원본을 완전히 읽는다.
   - `settlement-service/.claude/rules/clean-architecture.md`
   - `settlement-service/.claude/rules/domain-model.md`
   - `settlement-service/.claude/rules/controller-exception.md`
   - `settlement-service/.claude/rules/code-style.md`
   - `settlement-service/.claude/rules/swagger.md`
   - `settlement-service/.claude/rules/kafka-event.md`
   - `settlement-service/.claude/rules/security.md`
   - `settlement-service/.claude/rules/git-convention.md`
3. 파일별 적용 가능한 규칙만 검사한다. 특히 계층 의존 방향, 도메인 setter/Lombok, 예외·응답, 이벤트 계약, 테스트, secret을 확인한다.
4. commit 메시지에 `Co-Authored-By` trailer가 없는지 확인한다.
5. 이슈·PR 초안이 포함된 변경이나 검증 요청이면 번호 유형 표기, 담백한 문체, 실제 라벨, assignee·Type·Project·Status·reviewer 기본값도 확인한다.
6. `.claude/worktrees/`, 과거 plan·review 산출물은 현재 규칙으로 사용하지 않는다.
7. 결과를 심각도 순으로 `파일:라인 — 규칙 — 근거` 형식으로 보고한다. 불확실하면 `확인 필요`로 구분한다.
8. 위반이 없으면 확인한 규칙과 `위반 없음`을 명시한다.
