# 프로젝트 규칙

## 생성물 위치

git 저장소 루트는 상위 디렉토리(`beadv6_6_3JMT_BE`)지만, 새로 만드는 모든 생성물은
저장소 루트가 아니라 **이 모듈(`user-service/`) 안에** 둔다. 상대경로가 저장소 루트로
해석되지 않도록 항상 `user-service/...`를 명시한다.

- 문서·기획·설계·스펙: `user-service/docs/`
- 스킬 생성: `user-service/.claude/skills/`
- 에이전트 생성: `user-service/.claude/agents/`
- 그 외 도구·설정 산출물도 동일하게 모듈 하위에 만든다.

## 아키텍처 컨벤션

패키지 구조, 계층 책임, 포트 & 어댑터 규칙은 아래 문서를 따른다.

@.claude/rules/clean-architecture.md

도메인 모델·엔티티·Lombok 규칙은 아래 문서를 따른다.

@.claude/rules/domain-model.md

Controller·예외 처리 규칙은 아래 문서를 따른다.

@.claude/rules/controller-exception.md

코드 스타일(네이밍 케이스·import·빈 catch 등)은 아래 문서를 따른다.

@.claude/rules/code-style.md

API 문서화(Swagger/OpenAPI 애너테이션) 규칙은 아래 문서를 따른다.

@.claude/rules/swagger.md

## Git 컨벤션

커밋 메시지, 브랜치 명명, 병합 전략은 아래 문서를 따른다.

@.claude/rules/git-convention.md

---

## 도메인 모델

도메인 용어 사전 (테이블 정의 포함):

@../docs/domain-glossary/user.md

---

## API 명세

@../docs/api-spec/auth.md

@../docs/api-spec/user.md

---

## 기획 / 구현 현황

@docs/기획문서.md

---

## 작업 범위 (모듈 경계)

이 저장소(`beadv6_6_3JMT_BE`)는 여러 모듈로 구성되지만, 이 문서가 적용되는 작업 대상은 **유저 서비스 모듈
(`user-service/`)** 이다.

- **`user-service/` 안은 자유롭게 읽고 쓴다.** 파일 생성·수정·삭제 등 모든 작업을 허용한다.
- **다른 모듈은 읽기 전용(read-only)이다.** 참고를 위해 읽는 것은 허용하지만, 파일 생성·수정·삭제 등
  **쓰기 작업은 하지 않는다.** 다른 모듈의 코드를 바꿔야 할 일이 생기면 직접 고치지 말고 사용자에게 알린다.
- 공통 기능이 필요하면 `common-module`에 이미 있는 것을 사용하고, 없으면 사용자에게 추가를 요청한다.
  직접 추가하지 않는다.

---

## 개발 규칙

- 기능 구현 전 `docs/기획문서.md`를 확인해 구현 대상인지 확인 후 진행한다.
- 구현 완료 시 `docs/기획문서.md`의 해당 항목을 `- [ ]` → `- [x]`로 업데이트한다.
- **루트 docs/ 동기화**: 아래 내용이 변경되면 루트 docs/ 파일도 함께 수정한다.
  - API 명세 변경 → `docs/api-spec/auth.md` 또는 `docs/api-spec/user.md`
  - 에러코드 추가/변경 → `docs/error-codes.md`
  - 도메인 용어·테이블 변경 → `docs/domain-glossary/user.md`
