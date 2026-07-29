# PromptHub Documents

이 디렉터리는 프로젝트 전체 및 각 서비스 모듈의 문서를 통합 관리하는 곳입니다.

모든 서비스별 문서는 이곳 루트 `docs/` 디렉터리에 카테고리별로 모여 있습니다.
각 카테고리 내에서 서비스별로 디렉터리가 분리되어 있거나 파일명에 서비스 prefix가 붙어 있습니다.

## 디렉터리 구조 가이드

```text
docs/
├── api-spec/          # 서비스별 API 명세 (인증, 공통 헤더 등 포함)
├── architecture/      # 전체 아키텍처 및 각 서비스 아키텍처 (예: architecture/settlement/)
├── bug-reports/       # 버그 리포트 기록 (예: bug-reports/order/)
├── domain-glossary/   # 도메인 용어집
├── erd/               # 전체 및 서비스별 데이터베이스 스키마
├── guides/            # 개발 가이드라인 (Git 컨벤션, 코드 스타일, 마이그레이션 등)
├── integration/       # 서비스 간 통합 명세 및 계약 구조 (예: integration/order/)
├── planning/          # 기획 문서 및 로드맵
├── sql/               # 각 서비스별 실행/참조용 SQL 스크립트 (예: sql/settlement/)
├── superpowers/       # AI 에이전트(superpowers)가 생성한 계획(plans) 및 설계(specs)
├── trade-offs/        # 설계 결정 기록 (ADR)
└── trouble-shooting/  # 트러블슈팅 사례
```

## 문서 추가 규칙

1. 특정 서비스에만 해당하는 문서를 추가할 때, 각 카테고리 내 서비스 디렉터리(예: `docs/architecture/order/`)를 사용하세요.
2. 디렉터리로 분리하기 모호한 경우 파일명 앞에 서비스명 prefix를 붙여 작성하세요. (예: `docs/planning/user-기획문서.md`)
3. `api-spec`, `domain-glossary` 등 이미 하나의 파일로 관리되던 카테고리는 기존 컨벤션을 따릅니다.
