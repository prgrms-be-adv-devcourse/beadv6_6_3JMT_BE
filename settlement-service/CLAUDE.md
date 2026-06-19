# 프로젝트 규칙

## 생성물 위치

git 저장소 루트는 상위 디렉토리(`beadv6_6_3JMT_BE`)지만, 새로 만드는 모든 생성물은
저장소 루트가 아니라 **이 모듈(`settlement-service/`) 안에** 둔다. 상대경로가 저장소 루트로
해석되지 않도록 항상 `settlement-service/...`를 명시한다.

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

## Git 컨벤션

커밋 메시지, 브랜치 명명, 병합 전략은 아래 문서를 따른다.

@.claude/rules/git-convention.md
