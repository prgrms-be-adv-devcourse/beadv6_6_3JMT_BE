# Kibana 운영 대시보드 자동 등록 설계

## 배경

PromptHub 운영 환경에는 Gateway access 로그와 애플리케이션 구조화 로그가 이미 Elasticsearch에 수집되고 있다. 그러나 현재 Kibana 대시보드는 수동 생성 상태이며, 환경을 다시 구성할 때 동일한 대시보드를 재현할 수 없다. 기존 대시보드 일부 패널은 실제 수집 필드와 맞지 않아 운영 판단에 활용하기 어렵다.

이 설계는 GitHub 이슈 #670의 범위에 따라 운영자가 장애 징후를 빠르게 식별할 수 있는 세 개의 대시보드를 저장소에서 관리하고, ELK 배포 시 Kibana에 멱등 등록하는 방법을 정의한다.

## 목표

- 서비스 전체 상태, Gateway 이상 징후, 애플리케이션 런타임 장애를 목적별 대시보드로 분리한다.
- 대시보드와 Gateway Data View에 환경 독립적인 고정 ID를 사용한다.
- Kibana 9.4 Dashboard API로 동일한 요청을 반복해도 동일한 결과가 되도록 등록한다.
- 대시보드 정의를 독립 JSON 파일로 관리해 스키마 검증과 리뷰가 가능하게 한다.
- 기존 ELK 배포 및 검증 흐름에 대시보드 bootstrap을 포함한다.

## 비목표

- payment audit 및 결제·환불 감사 대시보드
- Kafka, Outbox, AI 요청, 인프라 metrics, SLO 전용 대시보드
- 신규 로그·메트릭 계측
- 임계치 기반 알림 또는 온콜 정책
- 기존 수동 `prompthub dashboard` 삭제

비목표 항목은 소유 영역과 필요한 telemetry가 정리된 후 별도 이슈로 다룬다.

## 선택한 접근

대시보드 본문은 Kibana Dashboard API 요청 JSON으로 버전 관리하고, Kubernetes Job이 고정 ID에 `PUT /api/dashboards/{id}` 요청을 수행한다.

Saved Object NDJSON 전체 import 방식은 Kibana 내부 저장 형식 결합도가 높고 diff가 불명확하므로 대시보드에는 사용하지 않는다. 다만 Data View는 현재 저장소의 기존 bootstrap 방식과 동일한 Saved Object import를 사용한다.

Kibana UI에서 직접 생성하는 방식은 빠르지만 환경 간 구성 drift를 막을 수 없어 배포 방식으로 사용하지 않는다.

## 배포 자산 구조

```text
k8s/addons/elk/
├── dashboards/
│   ├── gateway-data-view.ndjson
│   ├── prompthub-service-health.json
│   ├── prompthub-gateway-anomalies.json
│   └── prompthub-runtime-incidents.json
├── kibana-operations-dashboards.yaml
└── kustomization.yaml
```

- `kustomization.yaml`의 `configMapGenerator`가 네 파일을 `kibana-operations-dashboards` ConfigMap으로 패키징한다.
- ConfigMap 이름은 Job 재실행과 CD 명령의 예측 가능성을 위해 suffix hash 없이 고정한다.
- `kibana-operations-dashboards.yaml`은 bootstrap Job만 정의한다.
- CD는 기존 bootstrap Job과 함께 새 Job을 삭제 후 재생성하고 완료 상태를 기다린다.

## 식별자와 데이터 소스

| 종류 | 고정 ID | 인덱스 |
|---|---|---|
| Data View | `gateway-access` | `gateway-access-*` |
| Data View | `application-logs` | `application-logs-*` |
| Dashboard | `prompthub-service-health` | Gateway + Application |
| Dashboard | `prompthub-gateway-anomalies` | Gateway |
| Dashboard | `prompthub-runtime-incidents` | Application |

`application-logs` Data View는 기존 bootstrap 자산을 그대로 사용한다. 운영 환경에서 수동 생성된 Gateway Data View UUID는 환경마다 달라질 수 있으므로 참조하지 않는다.

## 등록 흐름

```mermaid
flowchart LR
    A["CD가 기존 bootstrap Job 삭제"] --> B["Kustomize 자산 적용"]
    B --> C["Kibana /api/status 준비 대기"]
    C --> D["Gateway Data View overwrite import"]
    D --> E["세 Dashboard를 고정 ID로 PUT"]
    E --> F["각 Dashboard를 GET하여 등록 확인"]
    F --> G["Job 완료"]
```

Job은 다음 규칙을 따른다.

1. Kibana 상태 API가 성공할 때까지 재시도한다.
2. `gateway-data-view.ndjson`을 `overwrite=true`로 import한다.
3. import 응답의 `success`와 `successCount`를 확인한다.
4. 각 Dashboard JSON을 해당 고정 ID에 PUT한다.
5. HTTP 오류가 발생하면 즉시 실패한다.
6. 각 Dashboard를 GET하고 응답의 ID를 확인한 뒤 완료한다.

Kubernetes Job의 `backoffLimit`, 보안 컨텍스트, 리소스 제한, 노드 배치 규칙은 기존 Kibana bootstrap Job과 동일한 기준을 적용한다.

## 대시보드 설계

모든 대시보드는 기본 조회 기간을 최근 24시간으로 설정하고, 운영자가 시간 범위를 변경할 수 있도록 한다.

### 서비스 상태

목적은 전체 트래픽과 오류 수준을 한 화면에서 파악하고 문제가 발생한 서비스 또는 route로 진입하는 것이다.

- KPI: 전체 요청, 2xx, 4xx, 5xx, p95 응답 시간, 애플리케이션 ERROR
- 추이: 요청량과 상태 코드 class, 응답 시간 percentile
- 분류: 요청이 많은 route, 5xx가 발생한 route, WARN/ERROR 서비스, 오류 발생 Pod
- 실제 수집량이 없는 `errorCode`, `errorType` 기반 패널은 사용하지 않는다.

### Gateway 이상 징후

목적은 인증·routing·upstream 장애와 지연을 Gateway 로그만으로 빠르게 구분하는 것이다.

- KPI: 401, 403, 404, 5xx, unknown route, 1초 이상 느린 요청
- 추이: 상태 코드 class, unknown route, 느린 요청
- 분류: unknown/404 path, 지연이 높은 route, HTTP method, 인증 여부
- 느린 요청의 초기 기준은 `duration >= 1000ms`로 두며 알림 임계치가 아니라 탐색 필터로만 사용한다.

### 런타임 장애

목적은 애플리케이션 WARN/ERROR가 발생한 서비스, Pod, logger와 기동 단계 장애 단서를 찾는 것이다.

- KPI: WARN, ERROR, 영향 서비스, 영향 Pod, stack trace 포함 오류
- 추이: 로그 level과 서비스별 WARN/ERROR
- 분류: ERROR logger, 오류 발생 Pod, `main` thread 및 Spring 기동 logger 기반 시작 단계 오류
- 원문 로그는 기존 `Application Logs` saved search와 Data View로 상세 탐색한다.

## 오류 처리와 안전성

- `curl --fail --silent --show-error`로 4xx/5xx 응답을 Job 실패로 처리한다.
- 모든 Dashboard는 PUT upsert로 등록해 중복 객체를 만들지 않는다.
- 기존 수동 대시보드는 삭제하거나 덮어쓰지 않는다.
- 결제 감사 Saved Object와 bootstrap Job은 수정하지 않는다.
- 로그 필드와 Dashboard query에는 토큰, Cookie, 요청 body 등 민감정보를 추가하지 않는다.
- Kibana는 계속 ClusterIP로 유지한다.

## 검증

### 정적 검증

- 모든 Dashboard JSON 문법 검사
- Kibana 9.4 Dashboard API OpenAPI 스키마 검사
- Dashboard의 Data View 참조가 `gateway-access` 또는 `application-logs`인지 검사
- Kubernetes manifest render 및 저장소 검증 스크립트 실행
- shell command와 workflow 기대 문자열 검사

### 배포 검증

- bootstrap Job 완료 확인
- 세 고정 ID의 Dashboard GET 성공 확인
- Kibana UI에서 세 대시보드가 열리는지 확인
- 최근 24시간 기준 각 패널이 오류 없이 렌더링되는지 확인
- Gateway 요청과 애플리케이션 WARN/ERROR 샘플이 의도한 패널에 반영되는지 확인

## 영향 범위

- `k8s/addons/elk`의 Kibana bootstrap 자산
- 수동 ELK CD workflow의 Job 삭제·대기 단계
- Kubernetes manifest 및 CD workflow 검증 스크립트
- ELK 운영 문서

애플리케이션 API, 데이터베이스, Kafka 이벤트, gRPC 계약에는 영향이 없다.
