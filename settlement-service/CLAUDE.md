# 프로젝트 규칙

## 작업 범위 (모듈 경계)

이 저장소(`beadv6_6_3JMT_BE`)는 여러 모듈로 구성되며, 담당(쓰기 가능) 범위는 다음과 같다.

- **`settlement-service/` 안은 자유롭게 읽고 쓴다.** 파일 생성·수정·삭제 등 모든 작업을 허용한다.
- **`user-service/`도 담당 모듈이다.** 자유롭게 읽고 쓴다. (셀러 정산 `sellersettlement` 포함 모듈 전체 —
  단, 컨벤션 정렬은 settlement 컨벤션을 따른다)
- **`admin-service/`는 정산 부분만 담당한다.** `com.prompthub.admin.settlement` 패키지(및 그 테스트·관련
  리소스)는 자유롭게 읽고 쓴다. **admin의 나머지 패키지는 읽기 전용**이다.
- **그 외 모듈(order·product·gateway 등)은 읽기 전용(read-only)이다.** 참고를 위해 읽는 것은 허용하지만,
  파일 생성·수정·삭제 등 **쓰기 작업은 하지 않는다.** 바꿔야 할 일이 생기면 직접 고치지 말고 사용자에게 알린다.

### 모듈별 적용 컨벤션

- `settlement-service` 작업: 이 문서와 아래 룰 7종을 따른다.
- `user-service` 작업: **`user-service/CLAUDE.md`와 `user-service/.claude/rules/`를 먼저 읽고 그쪽 컨벤션을
  따른다.** (자동 로드되지 않을 수 있으므로 작업 시작 시 직접 읽는다)
- `admin-service` 정산 작업: admin 에는 자체 룰 문서가 없다. **settlement 컨벤션(아래 룰 7종)을 준용**하되,
  admin 의 정산 엔티티는 재매핑이므로 스키마·전이 규칙의 소유자(`seller_settlement`→user-service,
  `settlement_source_line`→settlement-service)와 정합을 유지한다.

## 생성물 위치

git 저장소 루트는 상위 디렉토리(`beadv6_6_3JMT_BE`)지만, 새로 만드는 생성물은 저장소 루트가 아니라
**작업 대상 모듈 안에** 둔다. 상대경로가 저장소 루트로 해석되지 않도록 항상 `settlement-service/...`
처럼 모듈부터 명시한다.

- settlement-service 작업 → `settlement-service/` 아래 (문서·기획·설계·스펙: `docs/`,
  스킬: `.claude/skills/`, 에이전트: `.claude/agents/`)
- user-service 작업 → `user-service/` 아래 동일 구조
- admin-service 정산 작업 → 코드는 `admin-service/` 정산 패키지에, **문서·설계·스펙은 지금까지의 관행대로
  `settlement-service/docs/`에** 둔다. (admin 에는 docs 인프라가 없다)

## 아키텍처·코드 컨벤션

아래 룰 문서 7종을 따른다. PR 전 verify-rules 게이트가 검증하는 룰과 동일하다.

패키지 구조·계층 책임·포트 & 어댑터:

@.claude/rules/clean-architecture.md

도메인 모델·엔티티·Lombok:

@.claude/rules/domain-model.md

Controller·예외 처리:

@.claude/rules/controller-exception.md

코드 스타일(네이밍 케이스·import·빈 catch 등):

@.claude/rules/code-style.md

API 문서화(Swagger/OpenAPI 애너테이션):

@.claude/rules/swagger.md

시크릿·민감정보(커밋·로깅·노출 금지):

@.claude/rules/security.md

커밋 메시지·브랜치 명명·병합 전략:

@.claude/rules/git-convention.md
