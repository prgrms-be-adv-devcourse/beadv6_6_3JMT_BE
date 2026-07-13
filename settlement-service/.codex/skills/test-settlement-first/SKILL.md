---
name: test-settlement-first
description: settlement-service에서 계산·상태 전이·중복·권한·예외·금액처럼 핵심 비즈니스 규칙이 있는 기능을 구현할 때 실패하는 도메인 또는 애플리케이션 단위 테스트부터 작성한다. 정산 기능·서비스 메서드·API 구현 요청이 이러한 규칙을 포함하면 구현 코드 전에 자동으로 사용한다.
---

# 정산 테스트 우선 구현

1. 저장소 루트 기준으로 `settlement-service/CLAUDE.md`, `settlement-service/.claude/rules/clean-architecture.md`, `settlement-service/.claude/rules/domain-model.md`, `settlement-service/.claude/rules/code-style.md`를 읽는다.
2. 성공·실패·경계 규칙을 정리하고 Domain → Application Service 순서로 테스트한다.
3. 실제 DB·HTTP·Security·Batch·Transaction 검증은 구현 후 통합 테스트로 분리한다.
4. 첫 규칙 테스트를 작성하고 `./gradlew :settlement-service:test --tests "<test-class>"`로 RED를 확인한다.
5. 통과시키는 최소 구현만 추가해 GREEN을 확인한다.
6. 규칙별 RED→GREEN을 반복한 뒤 리팩터링하고 전체 정산 테스트를 실행한다.

- Domain 객체는 mock하지 않는다.
- Application Service는 Repository·외부 Client·Publisher 같은 외부 의존성만 mock한다.
- JUnit 5, AssertJ, Given/When/Then, 한국어 `@DisplayName`을 사용한다.
- `BigDecimal` 값은 필요하면 `isEqualByComparingTo`로 비교한다.
- 기존 버그는 재현 테스트가 실패하는 것을 먼저 확인한다.
