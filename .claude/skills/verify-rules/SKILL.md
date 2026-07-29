---
name: verify-rules
description: >-
  현재 브랜치의 변경 코드를 대상 서비스의 프로젝트 룰 기준으로 rule-checker 서브에이전트를 병렬
  디스패치해 검증합니다. 룰 목록은 서비스마다 다르므로 하드코딩하지 않고 대상 서비스의 룰 문서를
  런타임에 탐색합니다. 위반이 하나라도 있으면 게이트로 막고 위반 목록을 보여줍니다. 사용자가 "룰
  검증해줘", "컨벤션 어긴 데 없는지 봐줘", "PR 전에 룰 체크"라고 하거나, create-github-pr 스킬이 PR
  본문을 채우기 직전에 호출합니다. 코드를 수정하지는 않습니다.
---

# 룰 검증 게이트 (verify-rules)

변경 코드가 대상 서비스의 프로젝트 룰을 지키는지 검증한다. 룰 문서 하나당 `rule-checker`
서브에이전트 하나씩 **병렬로** 디스패치하고, 결과를 모아 게이트를 판정한다.
**위반이 하나라도 있으면 통과시키지 않는다.** 이 스킬은 검증만 한다 — 코드를 고치지 않고, PR을
만들지도 않는다.

> 저장소 루트의 공용 스킬이다. 룰 목록은 서비스마다 다르므로(예: settlement는 루트 공용 룰 +
> kafka-event, product는 루트 공용 룰 + architecture·product-api·testing·git-workflow 등)
> **고정 목록을 두지 않고 대상 서비스의 룰 문서를 탐색해서 검증한다.**

## 1. 변경분 수집과 대상 서비스 판정

base 브랜치를 정하고(현재 브랜치가 `feat/*`·`fix/*` 등 작업 브랜치면 base는 `develop`) 그에 대비한
변경을 모은다. base 판정은 create-github-pr과 동일 기준을 쓴다.

```bash
git rev-parse --abbrev-ref HEAD            # 현재(head) 브랜치
git diff <base>...HEAD --name-only         # 변경 파일 목록 (CHANGED_FILES)
git log <base>..HEAD --format="%s"         # 커밋 제목 (git 컨벤션 입력)
```

- 변경 파일이 하나도 없으면 검증할 게 없다. 알리고 멈춘다.
- 현재 브랜치가 base(develop/main) 자체면 검증 대상 브랜치가 아니다. 알리고 멈춘다.
- 변경 파일 경로의 최상위 모듈(예: `product-service/...`, `user-service/...`)로 **대상 서비스**를
  판정한다. 여러 서비스가 섞여 있으면 서비스별로 나눠 각각 검증한다.

## 2. 룰 문서 탐색 (하드코딩 금지)

대상 서비스에 적용할 룰 문서 목록을 **룰 이름(파일명) 단위로** 런타임에 수집한다. 위치가 아니라
이름이 검증 단위다 — 같은 이름이 두 위치에 있으면 서비스 쪽이 이긴다.

1. **루트 공용 룰**: `.claude/rules/*.md` (저장소 루트)는 모든 서비스에 기본 적용되는 팀 공용 룰이다.
   (예: `clean-architecture, domain-model, controller-exception, code-style, swagger, git-convention, security`)
2. **서비스 자체 룰**: `<service>/.claude/rules/*.md` 가 있으면 같은 이름의 루트 룰을 **덮어쓴다**
   (그 서비스에서는 서비스 버전만 적용, 루트 버전은 무시). 서비스에만 있고 루트에 없는 이름은
   그대로 추가된다.
   (예: `product-service/.claude/rules/{architecture,product-api,testing,git-workflow,kafka-event}.md` —
   이름이 `architecture`라 루트 `clean-architecture`와 겹치지 않으므로 **둘 다 적용**된다. 이렇게
   같은 목적의 룰이 다른 이름으로 공존하면 검증이 중복·상충될 수 있으니 발견 시 사용자에게 알린다.
   `settlement-service/.claude/rules/kafka-event.md`처럼 루트에 없는 이름은 그대로 추가만 된다.)
3. 최종 룰 목록 = 루트 룰과 서비스 룰의 합집합, 이름이 겹치면 서비스 룰로 대체.

수집 결과를 `RULE_NAME → RULE_FILE` 목록으로 만든다. 이 목록이 검증 단위다.

```bash
ls .claude/rules/*.md 2>/dev/null             # 루트 공용 룰
ls <service>/.claude/rules/*.md 2>/dev/null   # 서비스 자체 룰(이름 겹치면 이쪽 우선)
```

## 3. rule-checker 병렬 디스패치

수집한 룰 문서마다 `rule-checker` 서브에이전트(`.claude/agents/rule-checker.md`)를 하나씩 **병렬로**
디스패치한다. 각 서브에이전트에 다음을 전달한다.

- `RULE_NAME`, `RULE_FILE`(룰 문서 경로)
- 검증 입력: 변경 파일 목록, 필요한 경우 diff(추가 라인), 브랜치명·커밋 제목(git 컨벤션 룰용),
  `.gitignore`(보안 룰용)

각 서브에이전트는 해당 룰만 보고 위반 여부와 위반 항목(파일:위치·사유)을 반환한다.

## 4. 집계와 게이트

모든 서브에이전트 결과를 모은다.

- **위반이 하나라도 있으면 FAIL.** 위반 목록(룰명 → 대상:위치·사유)을 그대로 보여주고, 수정 후
  다시 실행하도록 안내한 뒤 멈춘다. 절대 통과 처리하지 않는다.
- 전 룰이 PASS/N/A면 게이트 통과다.

## 5. PR 템플릿 체크리스트 매핑 (create-github-pr에서 호출된 경우)

레포의 PR 템플릿(`.github/PULL_REQUEST_TEMPLATE.md`)을 단일 진실 공급원으로 보고, 검증한 룰을
템플릿 체크리스트 항목과 대응시킨다. 템플릿에 대응 항목이 있는 룰은 검증 결과(PASS면 `[x]`,
N/A면 `[ ]`+`(해당 변경 없음)`)를 create-github-pr에 넘겨 그대로 반영하게 한다. 템플릿에 없는
룰은 게이트 판정에만 쓴다.

## 경계

- 이 스킬은 **검증만** 한다. 코드 수정·커밋·PR 생성은 하지 않는다.
- 룰 목록을 이 파일에 고정하지 않는다 — 항상 대상 서비스의 룰 문서를 탐색해서 쓴다.
