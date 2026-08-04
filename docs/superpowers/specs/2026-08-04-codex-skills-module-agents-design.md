# Codex 공용 스킬 및 모듈 지침 재구성 설계

## 목적

정산 모듈 아래에 있던 Codex 스킬을 공식 자동 탐색 경로로 재배치하고, 저장소 공용 워크플로와 모듈 전용 워크플로를 분리한다. 루트와 모듈별 `AGENTS.md`는 개인 담당 범위가 아니라 해당 경로에서 누구나 따라야 하는 기술·검증 규칙만 제공한다.

## 배경

현재 Codex 스킬 6개는 `settlement-service/.codex/skills/`에 있다. 이 경로는 Codex의 공식 저장소 스킬 탐색 경로가 아니며, `settlement-service/AGENTS.md`도 루트 문맥에서 자동 탐색을 보장하지 않는다고 명시한다.

Codex의 공식 저장소 스킬 경로는 `.agents/skills/`이다. 루트의 `.agents/skills/`는 저장소 어디에서나 사용할 공용 스킬에 적합하고, 모듈 아래의 `.agents/skills/`는 해당 모듈에서만 필요한 스킬에 적합하다.

현재 `settlement-service/AGENTS.md`에는 특정 작업자의 담당 범위가 포함되어 있다. 공유 `AGENTS.md`는 모든 작업자가 읽으므로 개인 담당 범위를 저장소 지침으로 유지하지 않는다.

## 범위

- 공용 Codex 스킬 5개를 루트 `.agents/skills/`로 이동한다.
- 정산 전용 `test-settlement-first`를 `settlement-service/.agents/skills/`로 이동한다.
- 공용 스킬의 경로·범위 가정을 저장소 전체 기준으로 범용화한다.
- 루트 `AGENTS.md`에 공용 스킬과 모듈별 지침의 중립적인 라우팅을 추가한다.
- `settlement-service/AGENTS.md`를 정산 모듈 기술 규칙으로 정리한다.
- `user-service/AGENTS.md`를 새로 추가한다.
- Admin 정산 main/test 패키지에 각각 가까운 `AGENTS.md`를 추가한다.

## 범위 제외

- 루트 및 서비스별 `CLAUDE.md`는 수정하지 않는다.
- `.claude/**`의 규칙·스킬·에이전트는 수정하거나 이동하지 않는다.
- 개인별 담당 범위를 공유 `AGENTS.md`에 기록하지 않는다.
- `order-service/AGENTS.md`는 수정하지 않는다.
- 애플리케이션 코드, 테스트, 빌드 설정은 수정하지 않는다.

## 스킬 배치

### 루트 공용 스킬

다음 스킬을 `settlement-service/.codex/skills/`에서 `.agents/skills/`로 이동한다.

- `commit-project-changes`
- `create-project-branch`
- `create-project-issue`
- `create-project-pr`
- `verify-project-changes`

공용 스킬은 저장소의 어느 서비스·모듈·공유 영역에도 적용할 수 있다. 대상 경로에 적용되는 루트 및 하위 `AGENTS.md`를 완전히 읽고, 규칙이 충돌하거나 대상 변경 집합이 불명확하면 실행 전에 사용자에게 질문한다.

`commit-project-changes`와 `create-project-branch`에 하드코딩된 settlement/user/admin 정산 범위는 제거한다. 나머지 3개 스킬은 이미 저장소 전체 범위를 전제로 하므로 위치와 참조 경로를 중심으로 정리한다.

### 정산 전용 스킬

`test-settlement-first`는 `settlement-service/.agents/skills/test-settlement-first/`로 이동한다.

- 정산 계산, 상태 전이, 중복, 권한, 예외, 금액 규칙에만 적용한다.
- `settlement-service/AGENTS.md`와 필요한 루트 공용 규칙을 읽는다.
- `settlement-service/CLAUDE.md`를 Codex 실행 전제 조건으로 사용하지 않는다.
- 다른 서비스 작업에서는 암묵적으로 호출하지 않는다.

각 스킬의 `agents/openai.yaml`은 이동 후 `SKILL.md`의 이름·설명·기본 프롬프트와 일치하도록 갱신한다. 같은 이름의 스킬 사본은 남기지 않는다.

## AGENTS.md 계층

### 루트 AGENTS.md

루트 문서는 다음 책임만 갖는다.

- 저장소 전체 안전·검증 기본 규칙
- 공용 스킬 5개의 사용 조건
- 대상 경로에 가까운 `AGENTS.md`를 읽으라는 경로 기반 라우팅
- 다중 모듈 작업에서 실제 대상 모듈의 지침만 결합하는 원칙

라우팅은 특정 작업자나 팀원이 아니라 파일 경로를 기준으로 한다. Codex가 저장소 루트에서 시작해 하위 지침을 자동 로드하지 못하는 경우에도, 대상 경로에 해당하는 지침을 명시적으로 읽게 한다.

### settlement-service/AGENTS.md

정산 모듈에서 누구나 적용할 다음 내용만 유지한다.

- 정산 모듈의 경계와 공유 계약 접근 원칙
- 루트 공용 아키텍처·도메인·Controller·스타일·Swagger·Kafka·Git 규칙의 적용 조건
- 정산 핵심 규칙 변경 시 `test-settlement-first` 사용
- 모듈 테스트와 변경 검증 명령
- Codex 산출물과 스킬의 새 위치

기존의 user 전체와 Admin 정산 패키지를 개인 담당 범위로 허용하는 문구는 제거한다. `settlement-service/CLAUDE.md`를 필수로 읽으라는 지침도 제거한다.

### user-service/AGENTS.md

유저 모듈 전체에서 누구나 적용할 기술 지침을 제공한다.

- `user-service/**` 내부 작업에만 적용
- 모듈의 아키텍처·도메인·API·문서 동기화 규칙
- 다른 서비스의 내부 코드나 데이터베이스에 직접 의존하지 않는 원칙
- `./gradlew :user-service:test` 중심의 검증
- 공용 Git·검증 스킬의 사용 조건

개인 담당자 또는 다른 모듈까지 수정할 권한은 기록하지 않는다. `user-service/CLAUDE.md`는 수정하지 않으며 Codex 필수 지침으로 요구하지 않는다.

### Admin 정산 패키지 AGENTS.md

Admin 전체가 아니라 정산 패키지에만 적용되도록 다음 두 경로에 지침을 둔다.

- `admin-service/src/main/java/com/prompthub/admin/settlement/AGENTS.md`
- `admin-service/src/test/java/com/prompthub/admin/settlement/AGENTS.md`

main 지침은 Controller → Service → Repository의 3-tier 경계, Gateway 인증 책임, API·상태·합계 규칙의 테스트 동기화를 다룬다. test 지침은 main 정산 지침과 일치하는 테스트 범위, H2 기반 Repository 테스트 및 `./gradlew :admin-service:test` 검증을 다룬다.

두 문서는 Admin의 다른 도메인에 적용되지 않으며 다른 패키지 수정 권한을 부여하지 않는다.

## 스킬 사용 라우팅

| 작업 | 사용할 스킬 |
| --- | --- |
| 변경 집합 커밋 | `commit-project-changes` |
| 최신 base에서 작업 브랜치 생성 | `create-project-branch` |
| GitHub 이슈 초안·승인·생성 | `create-project-issue` |
| 전체 diff 검증 후 PR 생성·갱신 | `create-project-pr` |
| 커밋·PR 전 전체 변경 검증 또는 코드 리뷰 | `verify-project-changes` |
| 정산 핵심 비즈니스 규칙 구현·버그 수정 | `test-settlement-first` |

모듈별 `AGENTS.md`에는 스킬 본문을 복사하지 않고 사용 조건과 스킬 이름만 적는다. 실제 절차의 단일 진실 공급원은 각 `SKILL.md`다.

## 검증

- Skill Creator의 `quick_validate.py`로 이동한 6개 스킬을 모두 검증한다.
- 각 `agents/openai.yaml`이 현재 `SKILL.md`와 일치하는지 확인한다.
- 저장소에서 `settlement-service/.codex/skills/`의 낡은 참조가 남지 않았는지 검색한다.
- 루트 `.agents/skills/`에 공용 스킬 5개, 정산 모듈 `.agents/skills/`에 전용 스킬 1개만 존재하는지 확인한다.
- 루트 및 각 대상 경로의 `AGENTS.md` 라우팅과 적용 범위를 검토한다.
- `git diff --check`와 전체 diff를 확인한다.
- 코드 변경이 없으므로 문서·스킬 구조 검증을 기본으로 하며, 기존 브랜치의 전체 Gradle 테스트 결과는 PR 검증 근거로 유지한다.

## 완료 기준

- Codex 공용 스킬 5개가 루트 공식 탐색 경로에서 발견된다.
- 정산 전용 스킬은 정산 모듈 공식 탐색 경로에만 존재한다.
- 중복된 스킬 이름이나 낡은 `.codex/skills` 참조가 없다.
- 공용 Git 스킬이 특정 개인 담당 범위를 하드코딩하지 않는다.
- 루트 라우팅과 모듈 지침이 경로 기준으로 작동한다.
- 공유 `AGENTS.md`에 개인 담당 범위가 없다.
- `CLAUDE.md`와 `.claude/**`에는 변경이 없다.
