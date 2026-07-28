# ELK Application Log Services Normalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the `ai-service` Kubernetes runtime contract, prevent service-scoped Config environment omissions, and safely roll structured logs out until all eight business services are searchable by service and level.

**Architecture:** Keep the existing Fluent Bit and Logstash identity allowlist unchanged. Extend the Kubernetes Secret contract validator so every required no-default Config placeholder must be injected into the matching service workload's application container, then fix the missing AI Kafka environment and dependency gate. Deploy one service per Release workflow and stop at the first rollout, stdout, Elasticsearch, or Kibana failure.

**Tech Stack:** Bash, AWK, Kubernetes, Kustomize, Spring Boot/Gradle, GitHub Actions, Fluent Bit, Logstash, Elasticsearch, Kibana

## Global Constraints

- Work only on `feat/#648-elk-application-log-services`.
- Track the work in one issue and one PR, but run one Release workflow per service.
- Preserve the eight-service Fluent Bit and Logstash allowlist and all JSON/service identity validation.
- Do not add real Secret values, credentials, tokens, API keys, or generated diagnostic logs to Git.
- Treat `${KEY}` as a required Config value and `${KEY:default}` as optional for service-scoped validation.
- Inspect only the matching application container; init container or another service environment variables must not satisfy the contract.
- Treat `k8s/base/services/settlement/cronjob.yaml` container `settlement-service` as the settlement workload.
- Do not change the settlement schedule.
- Do not deliberately cause an `ERROR` in production.
- Do not proceed to the next service after any failed release or log verification gate.
- Use commit messages in `type: 변경 대상 작업 요약` format without parenthesized scopes.

---

### Task 1: AI Kafka Runtime Contract and Service-Scoped Validation

**Files:**
- Modify: `scripts/test-validate-k8s-secret-contract.sh:21-105`
- Modify: `scripts/validate-k8s-secret-contract.sh:19-62`
- Modify: `scripts/validate-k8s-manifests.sh:329-337`
- Modify: `k8s/base/services/ai/deployment.yaml:43-46,74-109`

**Interfaces:**
- Consumes: direct resources in `k8s/base/services/kustomization.yaml`, required `${KEY}` placeholders in each service Config profile, and the matching Deployment or CronJob YAML.
- Produces: `application_env_names(manifest, container_name)` output containing only environment variable names from the matching application container.
- Produces: an error in the form `deployed service ai-service requires KAFKA_BOOTSTRAP_SERVERS from config/src/main/resources/configs/ai-service.yml but k8s/base/services/ai/deployment.yaml does not inject it into container ai-service`.
- Produces: `ai-service` application environment variable `KAFKA_BOOTSTRAP_SERVERS` sourced from `runtime-secret.KAFKA_BOOTSTRAP_SERVERS`.

- [ ] **Step 1: Add the missing-environment fixture helper**

Add this helper after `assert_fails_with` in `scripts/test-validate-k8s-secret-contract.sh`. It removes one environment entry only from the named application container while preserving the following entry.

```bash
remove_container_env() {
  local manifest="$1"
  local container_name="$2"
  local env_name="$3"
  local output="${manifest}.tmp"

  awk -v container_name="${container_name}" -v env_name="${env_name}" '
    function indentation(value) {
      match(value, /^[[:space:]]*/)
      return RLENGTH
    }
    {
      indent = indentation($0)

      if (skipping_env) {
        if ($0 !~ /^[[:space:]]*$/ && indent <= env_indent) {
          skipping_env = 0
        } else {
          next
        }
      }

      if (in_container && $0 !~ /^[[:space:]]*$/ && indent <= container_indent) {
        in_container = 0
      }

      if ($0 ~ "^[[:space:]]*- name:[[:space:]]+" container_name "[[:space:]]*$") {
        in_container = 1
        container_indent = indent
        print
        next
      }

      if (in_container &&
          $0 ~ "^[[:space:]]*- name:[[:space:]]+" env_name "[[:space:]]*$") {
        skipping_env = 1
        env_indent = indent
        next
      }

      print
    }
  ' "${manifest}" > "${output}"
  mv "${output}" "${manifest}"
}
```

- [ ] **Step 2: Add the AI service-scoped regression case**

Append this case before the final success message. `cleanup` removes the previously mutated fixture, and `new_fixture` creates an isolated copy.

```bash
cleanup
new_fixture

remove_container_env \
  "${fixture_dir}/k8s/base/services/ai/deployment.yaml" \
  "ai-service" \
  "KAFKA_BOOTSTRAP_SERVERS"

ai_missing_kafka_output="${fixture_dir}/ai-missing-kafka.out"
assert_fails_with \
  "ai service-scoped Kafka environment" \
  "Kubernetes Secret contract validation failed: deployed service ai-service requires KAFKA_BOOTSTRAP_SERVERS from config/src/main/resources/configs/ai-service.yml but k8s/base/services/ai/deployment.yaml does not inject it into container ai-service" \
  "${ai_missing_kafka_output}"
```

- [ ] **Step 3: Run the validator test and verify RED**

Run:

```bash
bash scripts/test-validate-k8s-secret-contract.sh
```

Expected: exit code `1` with
`ai service-scoped Kafka environment expected failure`, because the current validator only
checks global Secret consumption.

- [ ] **Step 4: Record deployed service contracts in the validator**

In `scripts/validate-k8s-secret-contract.sh`, add a `deployed_service_contracts` array next to
`required_config_files` and `seen_services`.

```bash
declare -a required_config_files=("${CONFIG_ROOT}/application.yml")
declare -a seen_services=()
declare -a deployed_service_contracts=()
```

Inside the existing service loop, after adding `profile` to `required_config_files`, resolve the
matching workload and record relative paths for stable error output.

```bash
  profile_relative="config/src/main/resources/configs/${service}-service.yml"
  workload_relative="k8s/base/services/${service}/deployment.yaml"
  if [[ "${service}" == "settlement" ]]; then
    workload_relative="k8s/base/services/settlement/cronjob.yaml"
  fi
  workload="${ROOT_DIR}/${workload_relative}"
  [[ -f "${workload}" ]] ||
    fail "missing Kubernetes workload for deployed service: ${service}-service"

  deployed_service_contracts+=(
    "${service}-service|${profile_relative}|${workload_relative}"
  )
```

- [ ] **Step 5: Add application-container environment extraction**

Add this function after the service loop. It enters only the exact named container, enters its
`env` block, and stops at the next peer field or container.

```bash
application_env_names() {
  local manifest="$1"
  local container_name="$2"

  awk -v target="${container_name}" '
    function indentation(value) {
      match(value, /^[[:space:]]*/)
      return RLENGTH
    }
    {
      indent = indentation($0)

      if (in_container && $0 !~ /^[[:space:]]*$/ && indent <= container_indent) {
        in_container = 0
        in_env = 0
      }

      if ($0 ~ "^[[:space:]]*- name:[[:space:]]+" target "[[:space:]]*$") {
        in_container = 1
        in_env = 0
        container_indent = indent
        next
      }

      if (in_container && $0 ~ "^[[:space:]]+env:[[:space:]]*$") {
        in_env = 1
        env_indent = indent
        next
      }

      if (in_env && $0 !~ /^[[:space:]]*$/ && indent <= env_indent) {
        in_env = 0
      }

      if (in_env &&
          $0 ~ "^[[:space:]]*- name:[[:space:]]+[A-Z][A-Z0-9_]*[[:space:]]*$") {
        print $3
      }
    }
  ' "${manifest}" | sort -u
}
```

- [ ] **Step 6: Enforce each service profile against its workload**

Add this loop immediately after `application_env_names`. It extracts only no-default
placeholders and checks them against the matching application container.

```bash
for contract in "${deployed_service_contracts[@]}"; do
  IFS='|' read -r service_name profile_relative workload_relative <<< "${contract}"
  profile="${ROOT_DIR}/${profile_relative}"
  workload="${ROOT_DIR}/${workload_relative}"

  service_required_keys="$(
    grep -hoE '\$\{[A-Z][A-Z0-9_]*\}' "${profile}" \
      | sed -e 's/^${//' -e 's/}$//' \
      | sort -u ||
      true
  )"
  service_env_names="$(application_env_names "${workload}" "${service_name}")"

  while IFS= read -r key; do
    [[ -n "${key}" ]] || continue
    if ! grep -Fxq -- "${key}" <<< "${service_env_names}"; then
      fail "deployed service ${service_name} requires ${key} from ${profile_relative} but ${workload_relative} does not inject it into container ${service_name}"
    fi
  done <<< "${service_required_keys}"
done
```

- [ ] **Step 7: Run the test against the still-broken real baseline**

Run:

```bash
bash scripts/test-validate-k8s-secret-contract.sh
```

Expected: exit code `1` during the baseline assertion with the new
`deployed service ai-service requires KAFKA_BOOTSTRAP_SERVERS` error. This proves the validator
now detects the repository defect before the manifest is fixed.

- [ ] **Step 8: Inject the missing Kafka environment into AI**

Add this block in `k8s/base/services/ai/deployment.yaml` after `REDIS_PORT` and before
`USER_GRPC_SERVER_PORT`.

```yaml
            - name: KAFKA_BOOTSTRAP_SERVERS
              valueFrom:
                secretKeyRef:
                  name: runtime-secret
                  key: KAFKA_BOOTSTRAP_SERVERS
```

- [ ] **Step 9: Preserve the AI init dependency policy**

Do not add a Kafka wait to the AI init container. The existing repository policy intentionally
allows only Discovery, Config, and Redis waits there. Keep the current init commands unchanged
and verify that no `POSTGRES_`, `KAFKA_`, or `user-service` reference is present in that block.

Fix the policy check in `scripts/validate-k8s-manifests.sh` so it scopes the forbidden-variable
search to `init_container_block`, while still allowing the application container to receive
`KAFKA_BOOTSTRAP_SERVERS`:

```bash
    init_container_block="$(sed -n -E '/^[[:space:]]+initContainers:/,/^[[:space:]]+nodeSelector:/p' "${rendered}")"
    if grep -Eq 'POSTGRES_|KAFKA_' <<< "${init_container_block}"; then
      echo "AI service must not depend on PostgreSQL, Kafka, or User during Pod initialization" >&2
      exit 1
    fi
```

- [ ] **Step 10: Run the focused validator test and verify GREEN**

Run:

```bash
bash scripts/test-validate-k8s-secret-contract.sh
```

Expected:

```text
Kubernetes Secret contract validator tests passed.
```

The baseline must pass, and the copied fixture with the AI Kafka environment removed must fail
with the exact service-scoped message.

- [ ] **Step 11: Run manifest and AI regression checks**

Run:

```bash
bash scripts/validate-k8s-secret-contract.sh
bash scripts/validate-k8s-manifests.sh
./gradlew :ai-service:test
git diff --check
```

Expected: both Bash validators print their success messages, Gradle reports `BUILD SUCCESSFUL`,
and `git diff --check` produces no output.

- [ ] **Step 12: Commit the runtime contract fix**

```bash
git add \
  scripts/test-validate-k8s-secret-contract.sh \
  scripts/validate-k8s-secret-contract.sh \
  scripts/validate-k8s-manifests.sh \
  k8s/base/services/ai/deployment.yaml
git commit -m "fix: ai-service Kafka 런타임 계약 검증 추가"
```

---

### Task 2: Sequential Release and Log Verification Runbook

**Files:**
- Modify: `k8s/addons/elk/README.md:19-30,421-625,668-715`

**Interfaces:**
- Consumes: `Release - Develop` workflow inputs `release-services`, optional
  `manifest-services`, and confirmation value `RELEASE`.
- Produces: one-service-at-a-time release order
  `user-service → order-service → payment-service → admin-service → ai-service → settlement-service`.
- Produces: stdout, Elasticsearch, Kibana, log-level, request ID, and settlement one-off Job gates.

- [ ] **Step 1: Add the release stop-gate section**

After `## 시작 전 안전 원칙`, add a section titled
`## 서비스별 구조화 로그 순차 정상화`. State these exact rules:

```markdown
구조화 로그 이미지를 정상화할 때는 여러 서비스를 한 Release에 넣지 않는다.
`user-service`, `order-service`, `payment-service`, `admin-service`,
`ai-service`, `settlement-service` 순서로 각각 `Release - Develop`을 실행한다.
앞선 서비스의 rollout, 불변 image digest, stdout JSON, Elasticsearch 문서,
Kibana 문서를 모두 확인한 뒤에만 다음 서비스를 실행한다.

하나라도 실패하면 다음 Release를 실행하지 않는다. 실패한 Pod의 describe,
현재·이전 로그, 이벤트, image digest를 수집하고 해당 서비스부터 다시 시작한다.
수집 확인을 위해 Fluent Bit 또는 Logstash의 JSON·서비스 신원 검증을 완화하지 않는다.
```

Document that `product-service` and `notification-service` are verification targets but are not
part of this repair rollout because their structured-log images are already deployed.

- [ ] **Step 2: Add exact one-service workflow commands**

Add the following commands to the same section. Run and verify each block separately; do not
paste all six dispatches at once.

```bash
gh workflow run release-develop.yml \
  --ref develop \
  -f release-services=user-service \
  -f confirmation=RELEASE

gh workflow run release-develop.yml \
  --ref develop \
  -f release-services=order-service \
  -f confirmation=RELEASE

gh workflow run release-develop.yml \
  --ref develop \
  -f release-services=payment-service \
  -f confirmation=RELEASE

gh workflow run release-develop.yml \
  --ref develop \
  -f release-services=admin-service \
  -f confirmation=RELEASE

gh workflow run release-develop.yml \
  --ref develop \
  -f release-services=ai-service \
  -f manifest-services=ai-service \
  -f confirmation=RELEASE

gh workflow run release-develop.yml \
  --ref develop \
  -f release-services=settlement-service \
  -f confirmation=RELEASE
```

For each dispatch, retrieve and watch the latest manual run before continuing:

```bash
run_id="$(
  gh run list \
    --workflow release-develop.yml \
    --branch develop \
    --event workflow_dispatch \
    --limit 1 \
    --json databaseId \
    --jq '.[0].databaseId'
)"
gh run watch "${run_id}" --exit-status
```

- [ ] **Step 3: Document the per-service Kubernetes and stdout gate**

Add a reusable exact check where `SERVICE` is set to the service just released. Settlement uses
the separate command in Step 6.

```bash
SERVICE=user-service

kubectl -n prompthub rollout status \
  "deployment/${SERVICE}" \
  --timeout=10m

kubectl -n prompthub get deployment "${SERVICE}" \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="'"${SERVICE}"'")].image}{"\n"}'

kubectl -n prompthub logs "deployment/${SERVICE}" \
  --since=10m |
  jq -R -c \
    --arg service "${SERVICE}" \
    'fromjson? |
     select(.serviceName == $service and (.level | type == "string") and (.message | type == "string"))' |
  head -n 5
```

Repeat with `SERVICE=order-service`, `payment-service`, `admin-service`, and `ai-service`.
Passing requires a digest-pinned image reference and at least one JSON line.

- [ ] **Step 4: Document the Elasticsearch service and level gate**

With the existing Elasticsearch port-forward on `127.0.0.1:19200`, add:

```bash
SERVICE=user-service

curl -fsS \
  -H 'Content-Type: application/json' \
  'http://127.0.0.1:19200/application-logs-*/_search' \
  -d "{
    \"size\": 3,
    \"sort\": [{\"@timestamp\": \"desc\"}],
    \"query\": {
      \"bool\": {
        \"filter\": [
          {\"term\": {\"service.name\": \"${SERVICE}\"}},
          {\"exists\": {\"field\": \"level\"}},
          {\"exists\": {\"field\": \"message\"}}
        ]
      }
    }
  }" |
  jq '{total: .hits.total.value, documents: [.hits.hits[]._source]}'
```

Repeat for all eight allowlisted services. Passing requires `total >= 1` for each service.

Document these Kibana KQL examples:

```text
service.name: "user-service" and level: "INFO"
service.name: "user-service" and level: "WARN"
service.name: "user-service" and level: "ERROR"
```

Explain that a zero result means no event of that level may have occurred. Distinguish it from a
mapping/filter failure by comparing the service's unfiltered documents, `exists:level`, and the
Elasticsearch source. Use normal traffic for `INFO`, a documented non-mutating invalid request in
non-production for `WARN`, and historical or controlled non-production data for `ERROR`.

- [ ] **Step 5: Document request ID correlation**

Add this request and query sequence. It uses a generated UUID and searches both index families.

```bash
REQUEST_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"

curl -i \
  -H "X-Request-Id: ${REQUEST_ID}" \
  http://127.0.0.1:18000/api/v2/products

curl -fsS \
  -H 'Content-Type: application/json' \
  'http://127.0.0.1:19200/gateway-access-*,application-logs-*/_search' \
  -d "{
    \"size\": 20,
    \"sort\": [{\"@timestamp\": \"asc\"}],
    \"query\": {
      \"bool\": {
        \"minimum_should_match\": 1,
        \"should\": [
          {\"term\": {\"gateway.requestId.keyword\": \"${REQUEST_ID}\"}},
          {\"term\": {\"requestId\": \"${REQUEST_ID}\"}}
        ]
      }
    }
  }" |
  jq '[.hits.hits[] | {index: ._index, source: ._source}]'
```

Passing requires at least one Gateway document and one Servlet application document with the
same UUID. Do not require request ID correlation for Kafka, gRPC, Redis Pub/Sub, or settlement.

- [ ] **Step 6: Document settlement CronJob verification**

Add:

```bash
kubectl -n prompthub get cronjob settlement-weekly \
  -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[?(@.name=="settlement-service")].image}{"\n"}'

job_name="settlement-elk-verify-$(date +%s)"
kubectl -n prompthub create job \
  --from=cronjob/settlement-weekly \
  "${job_name}"

kubectl -n prompthub wait \
  --for=condition=complete \
  "job/${job_name}" \
  --timeout=2h

kubectl -n prompthub logs "job/${job_name}" |
  jq -R -c \
    'fromjson? |
     select(.serviceName == "settlement-service" and (.level | type == "string") and (.message | type == "string"))' |
  head -n 5
```

State that the existing CronJob schedule must remain `0 0 * * 1` in `Asia/Seoul`. Preserve the
one-off Job and its logs until Elasticsearch and Kibana evidence is captured; then delete only
that uniquely named Job.

- [ ] **Step 7: Correct the stale out-of-scope statement and checklist**

Replace the current `실습 범위 밖의 작업` bullet that says service Java logs are excluded with
an accurate statement that Kafka/gRPC/Redis/batch request ID propagation remains excluded. Add
completion checklist entries for:

```markdown
- [ ] 8개 allowlist 서비스가 `application-logs-*`에서 각각 조회된다.
- [ ] `level` 필드로 `INFO`, `WARN`, `ERROR`를 구분해 조회할 수 있다.
- [ ] Servlet 요청 하나가 Gateway와 애플리케이션에서 같은 `requestId`로 조회된다.
- [ ] settlement 일회성 Job의 JSON stdout과 Elasticsearch 문서를 확인했다.
```

- [ ] **Step 8: Verify and commit the runbook**

Run:

```bash
rg -n \
  -e 'user-service' \
  -e 'order-service' \
  -e 'payment-service' \
  -e 'admin-service' \
  -e 'level: "INFO"' \
  -e 'level: "WARN"' \
  -e 'level: "ERROR"' \
  -e 'settlement-elk-verify' \
  -e '다음 Release를 실행하지 않는다' \
  k8s/addons/elk/README.md
bash scripts/validate-k8s-manifests.sh
git diff --check
```

Expected: every runbook contract is found, manifest validation passes, and the diff check is
silent.

Commit:

```bash
git add k8s/addons/elk/README.md
git commit -m "docs: elk 서비스별 순차 배포 검증 절차 추가"
```

---

### Task 3: Full Verification and Pull Request

**Files:**
- Verify: `scripts/test-validate-k8s-secret-contract.sh`
- Verify: `scripts/validate-k8s-secret-contract.sh`
- Verify: `k8s/base/services/ai/deployment.yaml`
- Verify: `k8s/addons/elk/README.md`
- Verify: `docs/superpowers/specs/2026-07-28-elk-application-log-services-normalization-design.md`
- Verify: `docs/superpowers/plans/2026-07-28-elk-application-log-services-normalization.md`

**Interfaces:**
- Consumes: the Task 1 and Task 2 commits.
- Produces: a reviewable PR targeting `develop`, related to issue #648 without prematurely
  closing it before operational rollout is verified.

- [ ] **Step 1: Run all source verification commands**

Run:

```bash
bash scripts/test-validate-k8s-secret-contract.sh
bash scripts/validate-k8s-manifests.sh
./gradlew :ai-service:test
git diff --check
git status --short
```

Expected: both Bash validations pass, Gradle reports `BUILD SUCCESSFUL`, the diff check is silent,
and the status contains no uncommitted implementation files.

- [ ] **Step 2: Audit the branch scope**

Run:

```bash
git log --oneline origin/develop..HEAD
git diff --stat origin/develop...HEAD
git diff --name-only origin/develop...HEAD
```

Expected implementation scope:

```text
docs/superpowers/specs/2026-07-28-elk-application-log-services-normalization-design.md
docs/superpowers/plans/2026-07-28-elk-application-log-services-normalization.md
k8s/addons/elk/README.md
k8s/base/services/ai/deployment.yaml
scripts/test-validate-k8s-secret-contract.sh
scripts/validate-k8s-secret-contract.sh
```

Stop if unrelated files, generated logs, `.env` files, credentials, or Secret values appear.

- [ ] **Step 3: Push the branch**

```bash
git push -u origin feat/#648-elk-application-log-services
```

- [ ] **Step 4: Create the PR**

Create a ready PR with:

```text
Title: [FIX] elk - 애플리케이션 로그 서비스 정상화
Base: develop
Head: feat/#648-elk-application-log-services
Related issue: Related #648
```

The PR body must include:

- Why only `product-service` and `notification-service` are visible.
- The missing `ai-service` Kafka environment root cause.
- The service-scoped validator behavior and regression fixture.
- The exact source verification commands and results.
- No API, DB, Kafka topic, or Kafka payload change.
- No Fluent Bit/Logstash allowlist relaxation.
- A note that issue #648 remains open until the sequential operational rollout is verified.

- [ ] **Step 5: Wait for CI and review**

Run:

```bash
gh pr checks --watch
```

Expected: every required check passes. Do not merge without reviewer approval, a conflict-free
branch, and passing CI.

---

### Task 4: Sequential Operational Rollout and Issue Completion

**Files:**
- No repository file changes.
- External evidence: GitHub Release runs, Kubernetes rollout status, stdout JSON samples,
  Elasticsearch query totals, and Kibana screenshots or saved query results.

**Interfaces:**
- Consumes: approved PR, current `develop`, `Release - Develop`, and private cluster access.
- Produces: eight-service application log coverage and evidence attached to issue #648.

- [ ] **Step 1: Release the four services that precede AI before merging**

After PR CI and review are green, but before merging the AI manifest change, dispatch and fully
verify these services from current `develop`, one at a time:

```text
user-service
order-service
payment-service
admin-service
```

For each service, use the Task 2 workflow, rollout, stdout, Elasticsearch, and Kibana commands.
Record the Release run URL, immutable digest, latest JSON timestamp, and Elasticsearch hit count
in issue #648. Stop at the first failed gate.

- [ ] **Step 2: Merge the approved PR and watch the automatic AI manifest release**

Merge only after the first four services pass. The push to `develop` detects
`k8s/base/services/ai/deployment.yaml`, so watch the resulting `Release - Develop` run:

```bash
gh run list \
  --workflow release-develop.yml \
  --branch develop \
  --event push \
  --limit 5
```

Resolve the merged commit and its push-triggered Release run, then watch it:

```bash
merge_commit="$(
  gh pr view \
    --json mergeCommit \
    --jq '.mergeCommit.oid'
)"
ai_release_run_id="$(
  gh run list \
    --workflow release-develop.yml \
    --branch develop \
    --event push \
    --commit "${merge_commit}" \
    --limit 1 \
    --json databaseId \
    --jq '.[0].databaseId'
)"
gh run watch "${ai_release_run_id}" --exit-status
```

Passing requires `ai-service` rollout success, a digest-pinned image, JSON stdout, and an
Elasticsearch/Kibana document. If the query returns no run or automatic planning does not include
AI, manually dispatch the Task 2 AI command with both `release-services=ai-service` and
`manifest-services=ai-service`.

- [ ] **Step 3: Release and verify settlement**

Only after AI passes, dispatch `settlement-service` using the Task 2 command. Verify the CronJob
image, create the uniquely named one-off Job, wait for completion, and confirm its JSON stdout,
Elasticsearch document, and Kibana document. Do not alter the schedule.

- [ ] **Step 4: Verify all eight services in Elasticsearch**

Run one aggregation:

```bash
curl -fsS \
  -H 'Content-Type: application/json' \
  'http://127.0.0.1:19200/application-logs-*/_search' \
  -d '{
    "size": 0,
    "query": {
      "terms": {
        "service.name": [
          "user-service",
          "product-service",
          "order-service",
          "payment-service",
          "admin-service",
          "ai-service",
          "settlement-service",
          "notification-service"
        ]
      }
    },
    "aggs": {
      "services": {
        "terms": {
          "field": "service.name",
          "size": 8
        }
      },
      "levels": {
        "terms": {
          "field": "level",
          "size": 10
        }
      }
    }
  }' |
  jq '{
    total: .hits.total.value,
    services: [.aggregations.services.buckets[] | {service: .key, count: .doc_count}],
    levels: [.aggregations.levels.buckets[] | {level: .key, count: .doc_count}]
  }'
```

Passing requires all eight service buckets. The level aggregation proves `level` is filterable;
it does not require every level to have been generated by every service.

- [ ] **Step 5: Verify Kibana and request correlation**

In the `Application Logs` Data View, run and capture:

```text
service.name: ("user-service" or "product-service" or "order-service" or "payment-service" or "admin-service" or "ai-service" or "settlement-service" or "notification-service")
```

Then run:

```text
level: "INFO"
level: "WARN"
level: "ERROR"
```

Finally execute the Task 2 UUID request correlation check. Capture the Gateway and application
documents that share the UUID. If a level has no current event, record `0 events observed` after
confirming that `level` mapping and other level filters work; do not manufacture a production
fault.

- [ ] **Step 6: Post evidence and close issue #648**

Post a final issue comment containing:

- PR URL and merged commit.
- Six Release run URLs and immutable digests.
- Eight Elasticsearch service counts.
- Observed `INFO`, `WARN`, and `ERROR` counts.
- Request ID correlation evidence.
- Settlement one-off Job name and completion result.
- Confirmation that `gateway-access-*` and `products-v1` still return documents.

Close issue #648 only when every completion criterion is met. If any service remains missing,
leave the issue open and record the failed service, last successful service, diagnostic evidence,
and next action.
