# 프로젝트 규칙

## 작업 범위 (모듈 경계)

이 저장소(`beadv6_6_3JMT_BE`)는 여러 모듈로 구성되며, 담당 작업 범위는 **정산 서비스 모듈**, **유저 서비스 모듈**, **어드민 정산 패키지**다.

- **`settlement-service/` 안은 자유롭게 읽고 쓴다.** 파일 생성·수정·삭제 등 모든 작업을 허용한다.
- **`user-service/` 안은 자유롭게 읽고 쓴다.** 인증, 사용자, 판매자, Wishlist, Seller Settlement를 포함한 모듈 전체가 담당 범위다.
- **`admin-service/src/main/java/com/prompthub/admin/settlement/`와 `admin-service/src/test/java/com/prompthub/admin/settlement/`은 자유롭게 읽고 쓴다.** admin 모듈의 나머지 영역은 읽기 전용이다.
- 위 범위 밖의 다른 모듈은 참고용으로만 읽는다. 쓰기가 필요하면 직접 변경하지 말고 사용자에게 알린다.

## 생성물 위치

git 저장소 루트는 상위 디렉토리(`beadv6_6_3JMT_BE`)지만, 정산 전용 문서·기획·설계·스킬 같은 작업 생성물은
저장소 루트가 아니라 **이 모듈(`settlement-service/`) 안에** 둔다. 담당 소스·테스트·설정은 각 담당 모듈과 패키지 안에 둔다.

- 문서·기획·설계·스펙: `settlement-service/docs/`
- 스킬 생성: `settlement-service/.claude/skills/`
- 에이전트 생성: `settlement-service/.claude/agents/`
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

서비스 간 Kafka 이벤트(내부 비동기 통신) 메시지 구조·네이밍·발행/소비 규칙은 아래 문서를 따른다.

@.claude/rules/kafka-event.md

## Git 컨벤션

커밋 메시지, 브랜치 명명, 병합 전략은 아래 문서를 따른다.

@.claude/rules/git-convention.md
