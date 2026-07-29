# Kibana 운영 대시보드 한글화 설계

## 배경

GitHub 이슈 #670으로 등록한 세 운영 대시보드는 기능과 데이터 조회는 정상이나, 제목·설명·차트 라벨이 영문으로 표시된다. 운영자가 장애 상황에서 문구를 빠르게 이해할 수 있도록 사용자에게 노출되는 문구를 한글화한다.

## 목표

- 세 대시보드의 제목과 설명을 자연스러운 한국어로 표시한다.
- 패널 제목·설명, 차트 축·범례, 테이블 컬럼, 필터 컨트롤 라벨을 같은 용어 기준으로 한글화한다.
- 기술 식별자와 조회 계약을 유지해 데이터 조회 결과와 등록 방식에 영향을 주지 않는다.
- 생성 원본과 생성된 Dashboard API JSON이 항상 같은 문구를 갖도록 검증한다.

## 비목표

- Dashboard ID, Panel ID, Data View ID 변경
- Elasticsearch 인덱스 패턴과 필드명 변경
- Gateway Data View의 이름과 저장 객체 속성 변경
- KQL 표현식, 집계 방식, 패널 배치, 색상, 조회 기간 변경
- `payment audit` 대시보드 또는 후속 telemetry 대시보드 변경
- Kibana 자체 메뉴와 시스템 문구 한글화

## 한글화 원칙

사용자에게 의미를 전달하는 문장은 한국어로 바꾸고, 운영 현장에서 통용되는 기술 표기는 유지한다.

| 분류 | 표기 원칙 | 예시 |
|---|---|---|
| 제품명 | 원문 유지 | `PromptHub` |
| 프로토콜·상태 코드 | 원문 유지 | `HTTP`, `2xx`, `4xx`, `5xx` |
| 로그 레벨 | 원문 유지 | `WARN`, `ERROR` |
| 통계 지표 | 원문 유지 | `p50`, `p95`, `p99`, `ms` |
| Kubernetes 용어 | 원문 유지 | `Pod` |
| 일반 운영 용어 | 자연스러운 한국어 사용 | 요청 수, 응답 수, 지연 시간, 애플리케이션 서비스 |
| Gateway 표기 | 한글 표기로 통일 | 게이트웨이 |

대표 변경 예시는 다음과 같다.

- `[PromptHub] Service Health` → `[PromptHub] 서비스 상태`
- `Gateway error responses by status` → `상태 코드별 게이트웨이 오류 응답`
- `Routes ranked by p95 response time.` → `p95 응답 시간이 높은 라우트 순으로 표시합니다.`
- `Requests` → `요청 수`
- `Application service` → `애플리케이션 서비스`
- `Path` → `요청 경로`
- `UNKNOWN route` → `미매칭 라우트 (UNKNOWN)`

## 변경 구조

`scripts/generate-kibana-dashboards.mjs`를 표시 문구의 단일 원본으로 유지한다. 다음 사용자 노출 속성을 원본에서 한글화한다.

- Dashboard 루트의 `title`, `description`
- 각 Panel의 `config.title`, `config.description`
- Metric, XY, Data Table 시각화의 `label`과 `subtitle`
- Options List Control의 `title`

생성 스크립트로 다음 JSON을 다시 생성한다.

- `prompthub-service-health.json`
- `prompthub-gateway-anomalies.json`
- `prompthub-runtime-incidents.json`

기존에 값이 있는 설명은 한국어로 바꾸며, Metric Panel처럼 설명이 비어 있는 곳에는 새 설명을 추가하지 않는다. 필드명, KQL, ID처럼 화면 문구가 아닌 값은 그대로 유지한다. 예를 들어 `tableRow("gateway.path.keyword", "Path", 10)`에서는 `gateway.path.keyword`는 유지하고 라벨만 `요청 경로`로 변경한다.

## 검증

정적 검증은 다음 계약을 확인한다.

1. 세 Dashboard 루트 제목과 설명이 승인된 한국어 문구와 일치한다.
2. 사용자에게 표시되는 기존 영문 제목·설명·라벨이 남아 있지 않다.
3. 허용한 기술 표기와 모든 KQL·필드명·고정 ID가 유지된다.
4. 생성 스크립트의 출력과 버전 관리 JSON이 일치한다.
5. Kibana 9.4 Dashboard API 스키마와 Kubernetes manifest 검증이 계속 통과한다.

배포 검증은 기존 고정 ID에 세 대시보드를 다시 PUT한 뒤 다음을 확인한다.

- GET 응답의 Dashboard 제목과 설명이 한국어로 반환된다.
- Kibana UI에서 Dashboard·Panel·Control 문구가 한국어로 표시된다.
- 패널 렌더링 오류와 API 경고가 없다.
- 기존 데이터가 변경 전과 동일한 패널에 조회된다.

## 오류 처리와 롤백

등록은 기존 고정 ID에 대한 멱등 PUT을 사용한다. 일부 등록이 실패하면 오류 응답을 확인한 뒤 같은 명령을 재실행할 수 있다. 문구 변경으로 렌더링 문제가 발생하면 이전 커밋의 생성 원본으로 되돌리고 JSON을 재생성한 뒤 동일 ID에 다시 PUT한다.

## 영향 범위

- Kibana Dashboard API 요청 JSON의 사용자 노출 문구
- Dashboard JSON 생성기와 의미 검증 스크립트
- localhost 및 Kubernetes bootstrap으로 등록되는 세 운영 대시보드

애플리케이션 API, 데이터베이스, Kafka, gRPC, Elasticsearch 문서와 인덱스에는 영향이 없다.
