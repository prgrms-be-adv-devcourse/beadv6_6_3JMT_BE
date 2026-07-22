# Settlement Service 프로젝트 지침

## 소통 방식

- 대화와 이슈·PR 본문은 사람이 쓴 것처럼 담백하게 작성한다.
- 과장 수식어, 강조 남발, 불필요한 이모지를 사용하지 않는다.
- 핵심과 근거만 짧게 전달한다.
- 번호나 식별자를 언급할 때 유형을 함께 쓴다.
  - `#258 (이슈)`
  - `#238 (PR)`
  - `feat/#258-settlement-event (브랜치)`
  - `328700e (커밋)`
- 해석이 둘 이상이거나, 문서·규칙에 답이 없는 설계 결정이거나, 비가역·외부 작업이거나, 지시가 충돌하면 혼자 결정하지 않고 질문한다.
- 질문할 때는 선택지의 의미와 영향을 짧게 설명한다.

## 작업 범위와 모듈 경계

이 저장소는 여러 모듈로 구성되어 있다. 다음 범위는 작업 요청에 따라 읽고 쓸 수 있다.

- `settlement-service/` 전체
- `user-service/` 전체
- `admin-service/src/main/java/com/prompthub/admin/settlement/`
- `admin-service/src/test/java/com/prompthub/admin/settlement/`

위 경로 밖의 다른 모듈·패키지는 참고 목적으로만 읽는다. 범위 밖 파일 생성·수정·삭제가 필요하면 직접 변경하지 말고 필요한 변경과 이유를 사용자에게 알린다.

- `user-service`는 인증, 사용자, 판매자, Wishlist, Seller Settlement를 포함한 모듈 전체가 담당 범위다.
- `admin-service`는 `admin.settlement` 패키지만 쓰기 가능하며 admin 모듈 전체로 확대하지 않는다.
- `user-service`와 `settlement-service` 내부 설정, 리소스, 마이그레이션, 문서는 담당 범위에 포함된다. `common-module`, 저장소 루트 설정·문서와 admin 정산 패키지 밖의 변경이 필요하면 먼저 사용자에게 알린다.
- 기존 사용자 변경과 관련 없는 파일을 수정하거나 stage하지 않는다.

## 생성물 위치

허용된 담당 패키지의 소스·테스트 파일은 해당 패키지 안에 생성할 수 있다. 문서·기획·설계·스킬 같은 Codex 작업 산출물은 `settlement-service/` 안에 둔다. 상대 경로가 저장소 루트 기준으로 잘못 해석되지 않도록 응답과 작업에서 전체 경로를 명시한다.

- 문서·기획·설계·스펙: `settlement-service/docs/`
- Codex skill: `settlement-service/.codex/skills/`
- Codex 모듈 지침: `settlement-service/AGENTS.md`
- Claude 전용 skill·agent는 기존 `settlement-service/.claude/` 아래 구조를 유지한다.
- 그 밖의 Codex 도구·설정 산출물도 `settlement-service/.codex/` 아래에 둔다.
- 저장소 루트나 허용 범위 밖 모듈에 정산 서비스 전용 작업 산출물을 만들지 않는다.

## 작업 전 규칙 확인

작업 전에 `settlement-service/CLAUDE.md`를 읽고, 작업 유형에 해당하는 규칙 문서를 완전히 읽은 뒤 적용한다.

- 패키지 구조, 계층 책임, 의존 방향, 포트·어댑터:
  `settlement-service/.claude/rules/clean-architecture.md`
- 도메인 모델, 엔티티, Lombok:
  `settlement-service/.claude/rules/domain-model.md`
- Controller, 예외 처리, API 응답:
  `settlement-service/.claude/rules/controller-exception.md`
- 네이밍, import, 빈 catch 등 코드 스타일:
  `settlement-service/.claude/rules/code-style.md`
- Swagger/OpenAPI 문서화:
  `settlement-service/.claude/rules/swagger.md`
- Kafka 이벤트 구조, 네이밍, 발행·소비:
  `settlement-service/.claude/rules/kafka-event.md`
- 커밋 메시지, 브랜치 명명, 병합 전략:
  `settlement-service/.claude/rules/git-convention.md`

보안 관련 변경이나 규칙 검증 시에는 추가로 `settlement-service/.claude/rules/security.md`를 읽는다.

## 프로젝트 맥락

- `user-service`는 사용자 담당 모듈 전체다. `sellersettlement`는 2026-07-08부터 정산 컨벤션에 맞춘 상태이며 관련 작업은 `#236 (이슈)`을 참고한다. 기존 `seller` 패키지 정리는 백로그다.
- `admin-service`에서는 2026-07-09부터 `admin.settlement` 패키지만 사용자 담당 범위다. admin 모듈 전체가 담당 범위인 것은 아니다.
- 정산 어드민·셀러 분리는 3개 브랜치 계획이다.
  1. 어드민 이관: 완료
  2. 셀러 이동: `#233 (이슈)`, `#236 (이슈)`
  3. 기존 정산 기능 삭제: `#234 (이슈)`
- 역할 기준은 `settlement`가 정산 로그, `seller_settlement`가 운영 데이터의 단일 진실 공급원이다.
- Kafka 이벤트 규칙은 2026-07-09 팀 확정 사항이다. 공통 `EventMessage<T>`, `~Event`, UPPER_SNAKE `eventType`, `{domain}-events` 토픽, `eventId + consumerGroup` 멱등키를 사용한다. 세부 규칙의 원본은 `settlement-service/.claude/rules/kafka-event.md`다.
- `settlement-service`와 `user-service` 전체, `admin-service`의 `admin.settlement` main/test 패키지는 작업 요청에 따라 직접 수정할 수 있다. 그 밖의 admin 영역이나 공통 영역으로 범위를 넓혀야 하면 사용자에게 먼저 알린다.

## Codex skill 라우팅

Codex 전용 워크플로는 `settlement-service/.codex/skills/`에 있다. 범용 스킬도 이 모듈 하위에 보관하므로 `settlement-service` 외부나 저장소 루트 문맥에서는 자동 discovery를 보장하지 않는다. 그 문맥에서 사용할 때는 해당 `SKILL.md` 경로나 스킬 이름을 명시해 호출한다. 다음 문맥이면 해당 `SKILL.md`를 완전히 읽고 따른다.

- 정산 계산, 상태 전이, 중복, 권한, 예외, 금액 규칙 구현 또는 버그 수정:
  `settlement-service/.codex/skills/test-settlement-first/SKILL.md`
- 현재 브랜치나 작업 트리의 전체 변경 검증, PR 전 검증 또는 일반 코드 리뷰:
  `settlement-service/.codex/skills/verify-project-changes/SKILL.md`
- 정산 변경 커밋:
  `settlement-service/.codex/skills/commit-settlement-changes/SKILL.md`
- 정산 작업 브랜치 생성:
  `settlement-service/.codex/skills/create-settlement-branch/SKILL.md`
- 일반 GitHub 이슈 생성:
  `settlement-service/.codex/skills/create-project-issue/SKILL.md`
- 일반 Pull Request 생성 또는 갱신:
  `settlement-service/.codex/skills/create-project-pr/SKILL.md`

## 제외 대상

- `.claude/worktrees/`는 과거 Claude 작업 복사본이므로 현재 규칙이나 구현의 기준으로 사용하지 않는다.
- 과거 plan·review·eval 산출물은 현재 코드와 공식 규칙을 대체하지 않는다.
