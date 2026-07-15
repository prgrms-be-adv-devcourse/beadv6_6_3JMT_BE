payment-service에서 superpowers:brainstorming / writing-plans / subagent-driven-development 스킬 사용 시 따른다.

## 저장 경로 / 파일명

두 스킬의 기본 출력 경로(`docs/superpowers/specs/...`, `docs/superpowers/plans/...`)는 이 프로젝트에서 아래로 override한다.

| 스킬 | 산출물 | 저장 위치 | 파일명 |
|---|---|---|---|
| brainstorming | 계획 문서 | `.claude/plans/` | `{이슈번호}-{slug}.md` (slug는 [git-conventions.md](git-conventions.md) 브랜치명 규칙과 동일하게 영어 kebab-case) |
| writing-plans | 태스크 목록 문서 | `.claude/plans/` | brainstorming 문서명 + `-tasks` (예: `15-partial-refund.md` → `15-partial-refund-tasks.md`) |

## 기본 실행 경로

writing-plans 완료 후 스킬이 제시하는 "1. subagent-driven-development(권장) / 2. executing-plans" 중, 이 프로젝트는 **executing-plans를 기본값**으로 한다. subagent-driven-development는 사용자가 명시적으로 요청할 때만 사용한다.
