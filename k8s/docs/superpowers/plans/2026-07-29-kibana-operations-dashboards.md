# Kibana Operations Dashboards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register three reproducible PromptHub operations dashboards and their stable Gateway Data View during every ELK deployment.

**Architecture:** A deterministic Node generator produces Kibana 9.4 Dashboard API request JSON. Kustomize packages those JSON files and a Gateway Data View NDJSON file into a ConfigMap, and a hardened bootstrap Job imports the Data View and upserts each dashboard by stable ID. Repository validation scripts enforce dashboard semantics, rendered Kubernetes contracts, and CD orchestration.

**Tech Stack:** Kibana 9.4 Dashboard API, Elasticsearch Data Views, Kubernetes Job and ConfigMap, Kustomize, POSIX shell, Node.js 22, Ruby standard library

## Global Constraints

- Dashboard IDs are exactly `prompthub-service-health`, `prompthub-gateway-anomalies`, and `prompthub-runtime-incidents`.
- Data View IDs are exactly `gateway-access` and `application-logs`.
- Every dashboard uses `now-24h` to `now` as its default time range.
- Payment audit, payment/refund audit panels, new telemetry, alerts, Kafka/Outbox, AI, infrastructure metrics, and SLO dashboards are excluded.
- Existing manual `prompthub dashboard` objects are not deleted or overwritten.
- Kibana remains a ClusterIP service and no secret is added to the repository.
- Dashboard registration uses idempotent `PUT /api/dashboards/{id}` requests.

---

### Task 1: Add the executable dashboard contract test

**Files:**
- Create: `scripts/validate-kibana-dashboards.rb`
- Modify: `scripts/validate-k8s-manifests.sh`

**Interfaces:**
- Consumes: repository root resolved from `__dir__`
- Produces: `ruby scripts/validate-kibana-dashboards.rb` with exit code 0 only when all dashboard assets and their semantic contracts are valid

- [ ] **Step 1: Write the failing validator**

Create a Ruby standard-library validator with these exact contracts:

```ruby
#!/usr/bin/env ruby

require "json"

root = File.expand_path("..", __dir__)
dashboard_dir = File.join(root, "k8s/addons/elk/dashboards")
expected = {
  "prompthub-service-health" => %w[gateway-access application-logs],
  "prompthub-gateway-anomalies" => %w[gateway-access],
  "prompthub-runtime-incidents" => %w[application-logs],
}

errors = []

expected.each do |id, allowed_data_views|
  path = File.join(dashboard_dir, "#{id}.json")
  unless File.file?(path)
    errors << "missing dashboard: #{path}"
    next
  end

  dashboard = JSON.parse(File.read(path))
  errors << "#{id}: title is required" if dashboard["title"].to_s.empty?
  errors << "#{id}: invalid default time range" unless dashboard["time_range"] == {
    "from" => "now-24h",
    "to" => "now",
  }
  errors << "#{id}: panels must not be empty" unless dashboard["panels"].is_a?(Array) && !dashboard["panels"].empty?

  serialized = JSON.generate(dashboard)
  referenced = serialized.scan(/"ref_id":"([^"]+)"/).flatten |
    serialized.scan(/"data_view_id":"([^"]+)"/).flatten
  invalid = referenced.uniq - allowed_data_views
  errors << "#{id}: invalid Data View references #{invalid.join(", ")}" unless invalid.empty?
  errors << "#{id}: payment audit is out of scope" if serialized.match?(/payment[-_ ]audit/i)
end

data_view_path = File.join(dashboard_dir, "gateway-data-view.ndjson")
errors << "missing Gateway Data View" unless File.file?(data_view_path)

abort(errors.join("\n")) unless errors.empty?
puts "Kibana dashboard validation passed."
```

Extend the validator before committing so it also verifies:

- JSON root keys: `title`, `description`, `options`, `time_range`, `filters`, `query`, `panels`, `pinned_panels`
- unique panel IDs within each dashboard
- allowed panel types: `vis`
- allowed visualization types: `metric`, `xy`, `data_table`
- every KQL object has `language: "kql"` and a string `expression`
- the service dashboard references both Data Views
- the Gateway dashboard contains `gateway.routeId.keyword`, `gateway.status`, `gateway.durationMs`, and `gateway.routeId: "UNKNOWN"`
- the runtime dashboard contains `level`, `service.name`, `kubernetes.pod_name`, `logger_name`, and `stack_trace`
- no dashboard contains `errorCode`, `errorType`, or payment audit references
- `gateway-data-view.ndjson` is one valid line with type `index-pattern`, ID `gateway-access`, title `gateway-access-*`, and time field `@timestamp`

At the end of `scripts/validate-k8s-manifests.sh`, invoke:

```bash
ruby "${ROOT_DIR}/scripts/validate-kibana-dashboards.rb"
```

- [ ] **Step 2: Run the validator to verify it fails**

Run:

```bash
ruby scripts/validate-kibana-dashboards.rb
```

Expected: non-zero exit and `missing dashboard` for the three JSON files.

---

### Task 2: Generate the three Kibana Dashboard API payloads

**Files:**
- Create: `scripts/generate-kibana-dashboards.mjs`
- Create: `k8s/addons/elk/dashboards/gateway-data-view.ndjson`
- Create: `k8s/addons/elk/dashboards/prompthub-service-health.json`
- Create: `k8s/addons/elk/dashboards/prompthub-gateway-anomalies.json`
- Create: `k8s/addons/elk/dashboards/prompthub-runtime-incidents.json`

**Interfaces:**
- Consumes: no network or environment variables
- Produces: deterministic UTF-8 JSON ending in a newline; `--check` exits non-zero when committed JSON differs from generated output

- [ ] **Step 1: Add the stable Data View**

Write exactly one NDJSON object:

```json
{"type":"index-pattern","id":"gateway-access","attributes":{"title":"gateway-access-*","name":"Gateway Access","timeFieldName":"@timestamp"}}
```

- [ ] **Step 2: Implement deterministic dashboard generation**

Use stable panel IDs derived from SHA-256 and these base interfaces:

```javascript
const APP_DATA_VIEW = "application-logs";
const GATEWAY_DATA_VIEW = "gateway-access";

const kql = (expression = "") => ({ expression, language: "kql" });
const dataView = (refId) => ({ type: "data_view_reference", ref_id: refId });

function stableId(dashboardId, panelName) {
  const hex = createHash("sha256")
    .update(`${dashboardId}:${panelName}`)
    .digest("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-a${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

function dashboard(id, title, description, panels, pinnedPanels) {
  return {
    title,
    description,
    options: {
      auto_apply_filters: true,
      hide_panel_borders: false,
      hide_panel_titles: false,
      sync_colors: true,
      sync_cursor: true,
      sync_tooltips: true,
      use_margins: true,
    },
    time_range: { from: "now-24h", to: "now" },
    filters: [],
    query: kql(""),
    panels,
    pinned_panels: pinnedPanels,
    tags: [],
  };
}
```

Implement `metricPanel`, `xyTermsPanel`, `xyFiltersPanel`, `percentilePanel`, and `dataTablePanel` helpers using Kibana 9.4 Lens-compatible `vis` panel payloads. Use a 48-column grid and the following exact panel inventory:

| Dashboard | KPI panels | Trend panels | Breakdown panels |
|---|---|---|---|
| Service health | total, 2xx, 4xx, 5xx, p95, ERROR | route volume, status class, latency percentiles, WARN/ERROR by service | slowest routes, 5xx paths, ERROR logger, ERROR Pod |
| Gateway anomalies | 401, 403, 404, 5xx, UNKNOWN route, duration ≥ 1000ms | status class, UNKNOWN route, slow request | unknown/404 path, p95 by route, authenticated value, HTTP method |
| Runtime incidents | WARN, ERROR, affected service, affected Pod, stack trace ERROR, startup ERROR | level, WARN/ERROR by service, ERROR by Pod | ERROR logger, ERROR service/Pod, startup logger |

Use these field names exactly:

```javascript
const gatewayFields = {
  eventType: "gateway.eventType",
  status: "gateway.status",
  duration: "gateway.durationMs",
  route: "gateway.routeId.keyword",
  path: "gateway.path.keyword",
  method: "gateway.method.keyword",
  authenticated: "gateway.authenticated",
};

const applicationFields = {
  level: "level",
  service: "service.name",
  pod: "kubernetes.pod_name",
  logger: "logger_name",
  thread: "thread_name",
  stackTrace: "stack_trace",
};
```

The startup-error query is:

```text
level: "ERROR" and (thread_name: "main" or logger_name: ("org.springframework.boot.SpringApplication" or *Flyway*))
```

Each dashboard gets a time slider. Service health gets Application service and Gateway route controls, Gateway anomalies gets Gateway route and HTTP status controls, and Runtime incidents gets Application service and Log level controls.

Support deterministic checking:

```javascript
const check = process.argv.includes("--check");
for (const [id, payload] of Object.entries(dashboards)) {
  const output = `${JSON.stringify(payload, null, 2)}\n`;
  const path = resolve(outputDirectory, `${id}.json`);
  if (check && readFileSync(path, "utf8") !== output) {
    throw new Error(`generated dashboard is stale: ${path}`);
  }
  if (!check) writeFileSync(path, output, "utf8");
}
```

- [ ] **Step 3: Generate the JSON files**

Run:

```bash
node scripts/generate-kibana-dashboards.mjs
node scripts/generate-kibana-dashboards.mjs --check
```

Expected: both commands exit 0 and the second command does not modify files.

- [ ] **Step 4: Run semantic and Kibana schema validation**

Run:

```bash
ruby scripts/validate-kibana-dashboards.rb
ruby /private/tmp/validate-kibana-dashboard.rb /private/tmp/kibana-9.4-dashboard-openapi.yaml k8s/addons/elk/dashboards/prompthub-service-health.json
ruby /private/tmp/validate-kibana-dashboard.rb /private/tmp/kibana-9.4-dashboard-openapi.yaml k8s/addons/elk/dashboards/prompthub-gateway-anomalies.json
ruby /private/tmp/validate-kibana-dashboard.rb /private/tmp/kibana-9.4-dashboard-openapi.yaml k8s/addons/elk/dashboards/prompthub-runtime-incidents.json
```

Expected: four PASS results.

- [ ] **Step 5: Commit the validator and dashboard definitions**

```bash
git add \
  scripts/validate-kibana-dashboards.rb \
  scripts/validate-k8s-manifests.sh \
  scripts/generate-kibana-dashboards.mjs \
  k8s/addons/elk/dashboards
git commit -m "feat: k8s Kibana 운영 대시보드 정의 추가"
```

---

### Task 3: Add the idempotent Kibana bootstrap Job

**Files:**
- Create: `k8s/addons/elk/kibana-operations-dashboards.yaml`
- Modify: `k8s/addons/elk/kustomization.yaml`
- Modify: `scripts/validate-kibana-dashboards.rb`

**Interfaces:**
- Consumes: ConfigMap volume `/dashboards` and Kibana service `http://kibana.elk.svc.cluster.local:5601`
- Produces: Gateway Data View and three dashboards with stable IDs; Job `kibana-operations-dashboards-bootstrap`

- [ ] **Step 1: Extend the validator and verify the manifest test fails**

Make the validator render `k8s/addons/elk` with `kubectl kustomize` and assert:

```ruby
required_rendered_fragments = [
  "name: kibana-operations-dashboards",
  "name: kibana-operations-dashboards-bootstrap",
  "PUT \"${kibana_url}/api/dashboards/${dashboard_id}\"",
  "POST \"${kibana_url}/api/saved_objects/_import?overwrite=true\"",
  "prompthub-service-health.json",
  "prompthub-gateway-anomalies.json",
  "prompthub-runtime-incidents.json",
]
```

Run:

```bash
ruby scripts/validate-kibana-dashboards.rb
```

Expected: non-zero exit because the ConfigMap and Job do not exist yet.

- [ ] **Step 2: Package the files with Kustomize**

Add the Job manifest to `resources`, then add:

```yaml
configMapGenerator:
  - name: kibana-operations-dashboards
    files:
      - dashboards/gateway-data-view.ndjson
      - dashboards/prompthub-service-health.json
      - dashboards/prompthub-gateway-anomalies.json
      - dashboards/prompthub-runtime-incidents.json

generatorOptions:
  disableNameSuffixHash: true
  labels:
    app.kubernetes.io/part-of: prompthub
    app.kubernetes.io/component: observability
    app.kubernetes.io/managed-by: kustomize
```

- [ ] **Step 3: Implement the bootstrap Job**

Use the existing Kibana bootstrap security, scheduling, and resource contract. The container command performs:

```sh
kibana_url=http://kibana.elk.svc.cluster.local:5601
until curl --fail --silent --show-error "${kibana_url}/api/status" >/dev/null; do
  sleep 5
done

response=$(curl --fail --silent --show-error \
  -X POST "${kibana_url}/api/saved_objects/_import?overwrite=true" \
  -H "kbn-xsrf: kibana-operations-dashboards-bootstrap" \
  -F file=@/dashboards/gateway-data-view.ndjson)
echo "${response}" | grep -q '"success":true'
echo "${response}" | grep -q '"successCount":1'

for dashboard_id in \
  prompthub-service-health \
  prompthub-gateway-anomalies \
  prompthub-runtime-incidents; do
  curl --fail --silent --show-error \
    -X PUT "${kibana_url}/api/dashboards/${dashboard_id}" \
    -H "Content-Type: application/json" \
    -H "kbn-xsrf: kibana-operations-dashboards-bootstrap" \
    --data-binary "@/dashboards/${dashboard_id}.json" >/dev/null

  dashboard=$(curl --fail --silent --show-error \
    "${kibana_url}/api/dashboards/${dashboard_id}")
  echo "${dashboard}" |
    grep -Eq "\"id\"[[:space:]]*:[[:space:]]*\"${dashboard_id}\""
done
```

- [ ] **Step 4: Run Kubernetes and dashboard validation**

Run:

```bash
kubectl kustomize k8s/addons/elk >/dev/null
ruby scripts/validate-kibana-dashboards.rb
bash scripts/validate-k8s-manifests.sh
```

Expected: all commands exit 0.

- [ ] **Step 5: Commit the bootstrap assets**

```bash
git add k8s/addons/elk/kustomization.yaml k8s/addons/elk/kibana-operations-dashboards.yaml scripts/validate-kibana-dashboards.rb scripts/validate-k8s-manifests.sh
git commit -m "chore: k8s Kibana 운영 대시보드 bootstrap 추가"
```

---

### Task 4: Orchestrate dashboard bootstrap in ELK CD

**Files:**
- Modify: `.github/workflows/cd-selfhosted-kubernetes.yml`
- Modify: `scripts/validate-k8s-cd-workflow.sh`

**Interfaces:**
- Consumes: Kubernetes Job `kibana-operations-dashboards-bootstrap`
- Produces: every manual ELK deployment reruns and waits for dashboard registration

- [ ] **Step 1: Add failing workflow expectations**

Add these patterns to `manual_patterns`:

```bash
'kubectl delete job kibana-operations-dashboards-bootstrap -n elk --ignore-not-found'
'kubectl wait --for=condition=complete job/kibana-operations-dashboards-bootstrap -n elk --timeout=10m'
```

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: non-zero exit with `manual workflow missing contract`.

- [ ] **Step 2: Update the ELK deployment workflow**

Before applying the ELK package:

```bash
kubectl delete job kibana-operations-dashboards-bootstrap -n elk --ignore-not-found
```

After Kibana bootstrap dependencies complete:

```bash
kubectl wait --for=condition=complete job/kibana-operations-dashboards-bootstrap -n elk --timeout=10m
```

Keep the payment audit Job commands unchanged.

- [ ] **Step 3: Run workflow validation**

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: `CI/CD workflow validation passed.`

- [ ] **Step 4: Commit the CD change**

```bash
git add .github/workflows/cd-selfhosted-kubernetes.yml scripts/validate-k8s-cd-workflow.sh
git commit -m "chore: github-actions Kibana 대시보드 배포 단계 추가"
```

---

### Task 5: Document operation and complete verification

**Files:**
- Modify: `k8s/addons/elk/README.md`
- Modify: `k8s/README.md`

**Interfaces:**
- Consumes: dashboard IDs and bootstrap Job from Tasks 2–4
- Produces: deployment, verification, and scope documentation for operators

- [ ] **Step 1: Document the dashboard catalog**

Add a table with each dashboard ID, purpose, Data View, and default 24-hour range. State that payment audit is not part of these dashboards and the existing manual dashboard is preserved.

- [ ] **Step 2: Document registration and verification commands**

Include:

```bash
kubectl -n elk delete job kibana-operations-dashboards-bootstrap --ignore-not-found
kubectl apply --server-side -k k8s/addons/elk
kubectl -n elk wait --for=condition=complete \
  job/kibana-operations-dashboards-bootstrap \
  --timeout=10m
kubectl -n elk logs job/kibana-operations-dashboards-bootstrap
```

For a local SSH tunnel, document GET verification for the three stable IDs without exposing Kibana publicly.

- [ ] **Step 3: Run the full scoped verification**

Run:

```bash
node scripts/generate-kibana-dashboards.mjs --check
ruby scripts/validate-kibana-dashboards.rb
bash scripts/validate-k8s-manifests.sh
bash scripts/validate-k8s-cd-workflow.sh
/usr/bin/git -c core.fsmonitor=false diff --check
```

Expected: all commands exit 0 and both validation scripts print their success messages.

- [ ] **Step 4: Review scope and secrets**

Run:

```bash
rg -n -i 'payment[-_ ]audit|api[_ -]?key|bearer|password|cookie' \
  k8s/addons/elk/dashboards \
  k8s/addons/elk/kibana-operations-dashboards.yaml \
  scripts/generate-kibana-dashboards.mjs
```

Expected: no output.

- [ ] **Step 5: Commit the operator documentation**

```bash
git add k8s/addons/elk/README.md k8s/README.md
git commit -m "docs: k8s Kibana 운영 대시보드 사용법 추가"
```

- [ ] **Step 6: Inspect the final branch**

Run:

```bash
/usr/bin/git -c core.fsmonitor=false status --short
/usr/bin/git -c core.fsmonitor=false log --oneline develop..HEAD
/usr/bin/git -c core.fsmonitor=false diff --stat develop...HEAD
```

Expected: clean worktree, focused commits for issue #670, and only dashboard, Kubernetes, workflow, validation, and documentation files in scope.
