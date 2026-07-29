# PromptHub Documents

이 디렉터리는 프로젝트 전체 및 각 서비스 모듈의 문서를 통합 관리하는 곳입니다.

문서는 **현재 시스템 상태를 설명하는 레퍼런스**와 **작업·결정·이력 기록**으로 나뉩니다.
후자는 모두 `records/` 아래에 모여 있습니다.

## 디렉터리 구조 가이드

```text
docs/
├── api-spec/          # 서비스별 API 명세 (인증, 공통 헤더 등 포함)
├── architecture/      # 전체 아키텍처 및 각 서비스 아키텍처 (예: architecture/settlement/)
├── bug-reports/       # 버그 리포트 기록 (예: bug-reports/order/)
├── domain-glossary/   # 도메인 용어집
├── erd/               # 전체 및 서비스별 데이터베이스 스키마
├── guides/            # 개발 가이드라인 (Git 컨벤션, 코드 스타일, 마이그레이션 등)
├── records/           # 현재 스펙이 아닌 작업·결정·이력 기록
│   ├── planning/          # 기획 문서 및 로드맵·백로그
│   ├── trade-offs/        # 설계 트레이드오프·결정 기록
│   ├── troubleshooting/   # 트러블슈팅(인시던트) 사례
│   └── superpowers/       # 완료된 계획(plans)·설계(specs) 산출물 (AI 에이전트 산출물 보관)
├── adr/               # 아키텍처 결정 기록 (ADR) — 로컬 전용, git 미추적
├── README.md
├── error-codes.md     # 전역 에러 코드 레지스트리
└── grpc-contract-ownership.md
```

> `records/`는 "지금 시스템이 어떤 상태인가"(api-spec·architecture·erd 등)가 아니라
> "어떻게·왜 그렇게 했나"를 담습니다. 완료된 작업 산출물은 `records/superpowers/`로 보관합니다.
>
> `adr/`는 `.gitignore`에 의해 git 추적에서 의도적으로 제외됩니다(로컬 전용). 추적되는 설계
> 트레이드오프 기록은 `records/trade-offs/`에 둡니다.

## 문서 추가 규칙

1. 특정 서비스에만 해당하는 문서를 추가할 때, 각 카테고리 내 서비스 디렉터리(예: `docs/architecture/order/`)를 사용하세요.
2. 디렉터리로 분리하기 모호한 경우 파일명 앞에 서비스명 prefix를 붙여 작성하세요. (예: `docs/records/planning/user-기획문서.md`)
3. `api-spec`, `domain-glossary` 등 이미 하나의 파일로 관리되던 카테고리는 기존 컨벤션을 따릅니다.
4. 기획·트레이드오프·트러블슈팅·완료 산출물은 최상위가 아니라 `records/` 하위에 둡니다.
