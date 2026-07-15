payment-service에서 superpowers:brainstorming / writing-plans / subagent-driven-development 스킬 사용 시 따른다.

## 저장 경로 / 파일명

두 스킬의 기본 출력 경로(`docs/superpowers/specs/...`, `docs/superpowers/plans/...`)는 이 프로젝트에서 아래로 override한다.

| 스킬 | 산출물 | 저장 위치 | 파일명 |
|---|---|---|---|
| brainstorming | 계획 문서 | `.claude/plans/` | `{이슈번호}-{slug}.md` (slug는 [git-conventions.md](git-conventions.md) 브랜치명 규칙과 동일하게 영어 kebab-case) |
| writing-plans | 태스크 목록 문서 | `.claude/plans/` | brainstorming 문서명 + `-tasks` (예: `15-partial-refund.md` → `15-partial-refund-tasks.md`) |

## 구현 단계 자동 호출 제외

brainstorming, writing-plans는 계획 단계로 위 저장 규칙 그대로 사용한다. 단, **계획 문서 작성 이후 실제 코드 구현 단계에서는 superpowers 스킬을 자동 호출하지 않는다** — executing-plans, subagent-driven-development, test-driven-development, systematic-debugging, verification-before-completion, requesting-code-review, receiving-code-review 등 포함. 구현은 CLAUDE.md와 하위 `.claude/rules/*`만 따라 직접 진행한다.

사용자가 스킬을 명시적으로 지정해 요청하면(예: "TDD로 해줘") 그 때만 예외적으로 사용한다.
