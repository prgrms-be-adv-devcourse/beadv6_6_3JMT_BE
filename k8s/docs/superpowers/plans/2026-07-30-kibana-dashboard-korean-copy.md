# Kibana 운영 대시보드 한글화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 세 Kibana 운영 대시보드의 사용자 노출 제목·설명·라벨을 한국어로 바꾸고 기존 고정 ID에 재등록한다.

**Architecture:** `scripts/generate-kibana-dashboards.mjs`를 문구의 단일 원본으로 유지하고 세 Dashboard API JSON을 다시 생성한다. Ruby 의미 검증기에 한글 문구 계약을 먼저 추가해 기존 영문 JSON에서 실패하는 것을 확인한 뒤, 생성 원본을 한글화해 검증을 통과시킨다. Dashboard ID, Data View, KQL, 필드, 집계, 패널 ID와 배치는 변경하지 않는다.

**Tech Stack:** Node.js 22, Ruby standard library, Kibana 9.4 Dashboard API, Kubernetes Kustomize, curl

## Global Constraints

- 변경 대상은 `prompthub-service-health`, `prompthub-gateway-anomalies`, `prompthub-runtime-incidents` 세 Dashboard뿐이다.
- `PromptHub`, `HTTP`, `2xx`, `4xx`, `5xx`, `WARN`, `ERROR`, `p50`, `p95`, `p99`, `ms`, `Pod` 기술 표기는 유지한다.
- `Gateway` 일반 표시 문구는 `게이트웨이`로 통일한다.
- Dashboard ID, Panel ID, Data View ID, 인덱스 패턴, 필드명, KQL, 집계, 색상, 배치와 최근 24시간 기본 조회 기간은 변경하지 않는다.
- 값이 있는 설명은 한국어로 바꾸고 기존의 빈 Metric Panel 설명은 빈 값으로 유지한다.
- Gateway Data View와 payment audit 자산은 변경하지 않는다.
- 커밋 메시지는 허용 타입을 사용하고 소괄호 scope를 사용하지 않는다.

---

### Task 1: 한글 표시 문구 계약과 Dashboard JSON

**Files:**
- Modify: `scripts/validate-kibana-dashboards.rb`
- Modify: `scripts/generate-kibana-dashboards.mjs`
- Modify: `k8s/addons/elk/dashboards/prompthub-service-health.json`
- Modify: `k8s/addons/elk/dashboards/prompthub-gateway-anomalies.json`
- Modify: `k8s/addons/elk/dashboards/prompthub-runtime-incidents.json`

**Interfaces:**
- Consumes: `generate-kibana-dashboards.mjs`의 기존 Dashboard 생성 함수와 고정 ID
- Produces: 한국어 사용자 문구를 포함하는 동일 Dashboard API payload와 `validate_dashboard` 한글화 계약

- [ ] **Step 1: 한글 표시 문구를 식별하는 검증 계약 추가**

`scripts/validate-kibana-dashboards.rb`에 다음 계약과 순회 함수를 추가한다.

```ruby
LOCALIZED_ROOT_COPY = {
  "prompthub-service-health" => {
    title: "[PromptHub] 서비스 상태",
    description: "PromptHub 전체 요청량, HTTP 상태, 게이트웨이 지연 시간과 애플리케이션 WARN·ERROR 신호를 한 화면에서 확인합니다.",
  },
  "prompthub-gateway-anomalies" => {
    title: "[PromptHub] 게이트웨이 이상 징후",
    description: "게이트웨이 인증 실패, 라우팅 누락, 업스트림 오류와 지연 요청을 분석합니다.",
  },
  "prompthub-runtime-incidents" => {
    title: "[PromptHub] 런타임 장애 분석",
    description: "애플리케이션 WARN·ERROR 추이, 영향 서비스와 Pod, 스택 트레이스 및 기동 실패를 분석합니다.",
  },
}.freeze

ALLOWED_TECHNICAL_COPY = %w[
  2xx
  4xx
  5xx
  401
  403
  404
  p50
  p95
  p99
  Pod
].freeze

USER_VISIBLE_KEYS = %w[title description label subtitle text].freeze

def user_visible_copy(value)
  case value
  when Hash
    own_copy = USER_VISIBLE_KEYS.map do |key|
      candidate = value[key]
      candidate if candidate.is_a?(String)
    end.compact
    own_copy + value.values.flat_map { |child| user_visible_copy(child) }
  when Array
    value.flat_map { |child| user_visible_copy(child) }
  else
    []
  end
end
```

`validate_dashboard`에서 루트 문구의 정확한 값과 모든 사용자 노출 문자열을 확인한다.

```ruby
expected_copy = LOCALIZED_ROOT_COPY.fetch(id)
unless dashboard["title"] == expected_copy.fetch(:title)
  errors << "#{id}: localized title does not match"
end
unless dashboard["description"] == expected_copy.fetch(:description)
  errors << "#{id}: localized description does not match"
end

user_visible_copy(dashboard).reject(&:empty?).uniq.each do |copy|
  next if copy.match?(/[가-힣]/)
  next if ALLOWED_TECHNICAL_COPY.include?(copy)

  errors << "#{id}: user-facing copy must be Korean or approved technical notation: #{copy.inspect}"
end
```

- [ ] **Step 2: 검증을 실행해 RED 확인**

Run:

```bash
ruby scripts/validate-kibana-dashboards.rb
```

Expected: FAIL. 세 Dashboard에서 `localized title does not match`, `localized description does not match`와 기존 영문 표시 문구 오류가 출력되어야 한다.

- [ ] **Step 3: 서비스 상태 Dashboard 문구 변경**

`scripts/generate-kibana-dashboards.mjs`의 Service Health 문구를 다음 값으로 바꾼다.

| 현재 문구 | 변경 문구 |
|---|---|
| `[PromptHub] Service Health` | `[PromptHub] 서비스 상태` |
| `Shared operations view of PromptHub request volume, HTTP status, Gateway latency, and application WARN/ERROR signals.` | `PromptHub 전체 요청량, HTTP 상태, 게이트웨이 지연 시간과 애플리케이션 WARN·ERROR 신호를 한 화면에서 확인합니다.` |
| `Total requests` | `전체 요청 수` |
| `2xx responses` | `2xx 응답 수` |
| `4xx responses` | `4xx 응답 수` |
| `5xx responses` | `5xx 응답 수` |
| `p95 latency` | `p95 지연 시간` |
| `milliseconds` | `밀리초 (ms)` |
| `ERROR logs` | `ERROR 로그 수` |
| `Request volume by Gateway route` | `게이트웨이 라우트별 요청량` |
| `Gateway requests over time, split by route ID.` | `게이트웨이 요청량을 라우트 ID별 시계열로 표시합니다.` |
| `HTTP responses by status class` | `상태 코드 구간별 HTTP 응답` |
| `Gateway responses over time, split into successful and error classes.` | `게이트웨이 응답을 성공 및 오류 상태 코드 구간별 시계열로 표시합니다.` |
| `Gateway response time percentiles` | `게이트웨이 응답 시간 백분위수` |
| `p50, p95, and p99 response time across Gateway requests.` | `게이트웨이 요청의 p50, p95, p99 응답 시간을 표시합니다.` |
| `Top 10 slowest Gateway routes` | `p95 지연이 높은 게이트웨이 라우트 TOP 10` |
| `Routes ranked by p95 response time.` | `p95 응답 시간이 높은 라우트 순으로 표시합니다.` |
| `Top 10 paths returning 5xx` | `5xx 응답이 많은 요청 경로 TOP 10` |
| `Gateway paths ranked by server-error response count.` | `서버 오류 응답 수가 많은 게이트웨이 요청 경로 순으로 표시합니다.` |
| `WARN and ERROR logs by service` | `서비스별 WARN·ERROR 로그` |
| `Potential incidents over time, split by application service.` | `잠재적 장애 신호를 애플리케이션 서비스별 시계열로 표시합니다.` |
| `Top ERROR loggers` | `ERROR 로그 상위 로거` |
| `Loggers ranked by ERROR occurrence count.` | `ERROR 발생 횟수가 많은 로거 순으로 표시합니다.` |
| `Top Pods with ERROR logs` | `ERROR 로그 상위 Pod` |
| `Kubernetes Pods ranked by ERROR occurrence count.` | `ERROR 발생 횟수가 많은 Kubernetes Pod 순으로 표시합니다.` |

공통 라벨은 다음처럼 변경한다.

```text
Requests            -> 요청 수
Responses           -> 응답 수
Gateway route       -> 게이트웨이 라우트
Status class        -> 상태 코드 구간
p95, ms             -> p95 지연 시간 (ms)
5xx responses       -> 5xx 응답 수
Path                -> 요청 경로
Method              -> HTTP 메서드
WARN / ERROR logs   -> WARN·ERROR 로그 수
Application service -> 애플리케이션 서비스
ERROR logs          -> ERROR 로그 수
Logger              -> 로거
```

- [ ] **Step 4: 게이트웨이 이상 징후 Dashboard 문구 변경**

다음 고유 문구를 변경하고, Task 1 Step 3의 공통 라벨을 같은 값으로 재사용한다.

```text
[PromptHub] Gateway Anomalies
  -> [PromptHub] 게이트웨이 이상 징후
Gateway-focused view for authentication failures, routing misses, upstream errors, and slow requests.
  -> 게이트웨이 인증 실패, 라우팅 누락, 업스트림 오류와 지연 요청을 분석합니다.
401 responses -> 401 응답 수
403 responses -> 403 응답 수
404 responses -> 404 응답 수
5xx responses -> 5xx 응답 수
UNKNOWN route -> 미매칭 라우트 (UNKNOWN)
Requests ≥ 1000ms -> 1000ms 이상 지연 요청
Gateway error responses by status -> 상태 코드별 게이트웨이 오류 응답
Authentication, routing, and upstream errors over time.
  -> 인증, 라우팅, 업스트림 오류를 상태 코드별 시계열로 표시합니다.
HTTP status -> HTTP 상태 코드
Unknown route and 404 trend -> 미매칭 라우트와 404 추이
Unmatched routes compared with all 404 responses.
  -> 미매칭 라우트와 전체 404 응답을 비교합니다.
Routing anomaly -> 라우팅 이상 유형
Slow requests by route -> 라우트별 지연 요청
Requests taking at least one second, split by route.
  -> 1초 이상 걸린 요청을 라우트별 시계열로 표시합니다.
Slow requests -> 지연 요청 수
Gateway latency percentiles -> 게이트웨이 지연 시간 백분위수
p50, p95, and p99 response time for anomaly correlation.
  -> 이상 징후와 비교할 수 있도록 p50, p95, p99 응답 시간을 표시합니다.
Top unknown and 404 paths -> 미매칭 및 404 요청 경로
Paths most often associated with routing misses.
  -> 라우팅 누락과 가장 자주 연관된 요청 경로 순으로 표시합니다.
Status -> 상태 코드
Routes with highest p95 latency -> p95 지연이 높은 라우트
Gateway routes ranked by p95 response time.
  -> p95 응답 시간이 높은 게이트웨이 라우트 순으로 표시합니다.
Requests by authentication state -> 인증 여부별 요청
Gateway requests split by authenticated state.
  -> 게이트웨이 요청을 인증 여부별로 구분합니다.
Authenticated -> 인증 여부
Requests by HTTP method -> HTTP 메서드별 요청
Gateway requests split by HTTP method.
  -> 게이트웨이 요청을 HTTP 메서드별로 구분합니다.
HTTP method -> HTTP 메서드
```

- [ ] **Step 5: 런타임 장애 분석 Dashboard 문구 변경**

다음 고유 문구를 변경하고, 앞 단계의 공통 라벨을 같은 값으로 재사용한다.

```text
[PromptHub] Runtime Incidents
  -> [PromptHub] 런타임 장애 분석
Application-focused view for WARN/ERROR trends, affected services and Pods, stack traces, and startup failures.
  -> 애플리케이션 WARN·ERROR 추이, 영향 서비스와 Pod, 스택 트레이스 및 기동 실패를 분석합니다.
WARN logs -> WARN 로그 수
ERROR logs -> ERROR 로그 수
Affected services -> 영향받은 서비스 수
Affected Pods -> 영향받은 Pod 수
ERROR with stack trace -> 스택 트레이스 포함 ERROR
Startup ERROR -> 기동 단계 ERROR
Application logs by level -> 레벨별 애플리케이션 로그
Structured application logs over time, split by level.
  -> 구조화된 애플리케이션 로그를 레벨별 시계열로 표시합니다.
Log level -> 로그 레벨
Log events -> 로그 건수
ERROR logs by Kubernetes Pod -> Kubernetes Pod별 ERROR 로그
ERROR logs over time, split by Pod to identify isolated failures.
  -> 격리된 장애를 식별할 수 있도록 ERROR 로그를 Pod별 시계열로 표시합니다.
Services and Pods with ERROR logs -> ERROR 발생 서비스와 Pod
Affected services and Pods ranked by ERROR occurrence count.
  -> ERROR 발생 횟수가 많은 서비스와 Pod 순으로 표시합니다.
Startup ERROR loggers -> 기동 단계 ERROR 상위 로거
Main-thread and Spring startup loggers ranked by ERROR count.
  -> main 스레드와 Spring 기동 로거를 ERROR 발생 횟수 순으로 표시합니다.
Startup ERROR logs -> 기동 단계 ERROR 로그 수
Thread -> 스레드
```

`percentilePanel`의 공통 Y축 제목도 변경한다.

```javascript
axis: xyAxis("응답 시간 (ms)"),
```

- [ ] **Step 6: Dashboard JSON 재생성**

Run:

```bash
node scripts/generate-kibana-dashboards.mjs
```

Expected: 세 JSON 파일만 생성 원본의 한국어 문구로 갱신된다.

- [ ] **Step 7: GREEN과 생성 결정성 확인**

Run:

```bash
ruby scripts/validate-kibana-dashboards.rb
node scripts/generate-kibana-dashboards.mjs --check
```

Expected:

```text
Kibana dashboard validation passed.
Validated 3 Kibana dashboards.
```

- [ ] **Step 8: 구조·배포 회귀 검증**

Run:

```bash
bash scripts/validate-k8s-manifests.sh
bash scripts/validate-k8s-cd-workflow.sh
git diff --check
```

Expected: Kubernetes manifest, CI/CD workflow와 diff 검사가 모두 exit 0이다.

- [ ] **Step 9: 변경 범위 확인 후 커밋**

Run:

```bash
git diff -- scripts/validate-kibana-dashboards.rb scripts/generate-kibana-dashboards.mjs k8s/addons/elk/dashboards/prompthub-service-health.json k8s/addons/elk/dashboards/prompthub-gateway-anomalies.json k8s/addons/elk/dashboards/prompthub-runtime-incidents.json
git add scripts/validate-kibana-dashboards.rb scripts/generate-kibana-dashboards.mjs k8s/addons/elk/dashboards/prompthub-service-health.json k8s/addons/elk/dashboards/prompthub-gateway-anomalies.json k8s/addons/elk/dashboards/prompthub-runtime-incidents.json
git commit -m "feat: k8s Kibana 운영 대시보드 문구 한글화"
```

Expected: 표시 문구와 한글화 검증만 포함된 단일 커밋이 생성된다.

### Task 2: localhost Kibana 재등록과 화면 검증

**Files:**
- Modify: none

**Interfaces:**
- Consumes: Task 1에서 생성한 세 Dashboard API JSON과 실행 중인 `http://127.0.0.1:5601`
- Produces: 기존 고정 ID에 한글 문구로 갱신된 세 Kibana Dashboard

- [ ] **Step 1: Kibana 준비 상태 확인**

Run:

```bash
curl --fail --silent --show-error http://127.0.0.1:5601/api/status |
  jq '{version:.version.number,status:.status.overall.level}'
```

Expected: Kibana `9.4.3`, status `available`.

- [ ] **Step 2: 세 Dashboard를 동일 ID로 덮어쓰기**

Run:

```bash
for dashboard_id in \
  prompthub-service-health \
  prompthub-gateway-anomalies \
  prompthub-runtime-incidents
do
  curl --fail --silent --show-error \
    -X PUT "http://127.0.0.1:5601/api/dashboards/${dashboard_id}" \
    -H "Content-Type: application/json" \
    -H "kbn-xsrf: kibana-dashboard-korean-copy" \
    --data-binary "@k8s/addons/elk/dashboards/${dashboard_id}.json" \
    -o "/private/tmp/kibana-${dashboard_id}-ko-put.json"
done
```

Expected: 세 PUT 요청이 모두 exit 0이고 응답의 `warnings`가 빈 배열이다.

- [ ] **Step 3: GET 응답의 제목·설명·패널 수 확인**

Run:

```bash
for dashboard_id in \
  prompthub-service-health \
  prompthub-gateway-anomalies \
  prompthub-runtime-incidents
do
  curl --fail --silent --show-error \
    "http://127.0.0.1:5601/api/dashboards/${dashboard_id}" \
    -o "/private/tmp/kibana-${dashboard_id}-ko-get.json"
  jq '{id,title:.data.title,description:.data.description,panels:(.data.panels|length),warnings:(.warnings//[])}' \
    "/private/tmp/kibana-${dashboard_id}-ko-get.json"
done
```

Expected:

```text
prompthub-service-health: [PromptHub] 서비스 상태, 14 panels, 0 warnings
prompthub-gateway-anomalies: [PromptHub] 게이트웨이 이상 징후, 14 panels, 0 warnings
prompthub-runtime-incidents: [PromptHub] 런타임 장애 분석, 12 panels, 0 warnings
```

- [ ] **Step 4: Kibana UI 렌더링 검증**

다음 URL을 차례로 열어 Dashboard 제목, Panel 제목, 축·범례·테이블·필터 컨트롤 문구와 실제 데이터 렌더링을 확인한다.

```text
http://127.0.0.1:5601/app/dashboards#/view/prompthub-service-health
http://127.0.0.1:5601/app/dashboards#/view/prompthub-gateway-anomalies
http://127.0.0.1:5601/app/dashboards#/view/prompthub-runtime-incidents
```

Expected: 세 화면에서 embeddable 오류와 alert callout이 0개이고, 제목과 사용자 노출 문구가 승인된 한국어로 표시된다.

- [ ] **Step 5: 최종 저장소 상태 확인**

Run:

```bash
git status --short
git log -2 --oneline
```

Expected: 작업 트리가 clean이며 설계 커밋과 한글화 구현 커밋이 현재 브랜치의 최근 커밋으로 표시된다.
