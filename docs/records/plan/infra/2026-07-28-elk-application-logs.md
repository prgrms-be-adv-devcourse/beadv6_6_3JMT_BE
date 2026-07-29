# ELK Application Logs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collect structured SLF4J logs from eight business applications in `application-logs-*`, correlate Servlet request logs with Gateway `X-Request-Id`, and provide a secure seven-day Kibana search path without changing the existing Gateway index.

**Architecture:** Spring Boot emits one-line Logstash JSON and a common Servlet auto-configuration adds UUID request IDs to MDC. Fluent Bit tails only the eight allowed application container names, while Logstash validates Kubernetes and application identity, redacts sensitive data, normalizes `service.name`, and routes application logs to a dedicated index. Elasticsearch ILM, Kibana saved objects, manual ELK deployment, and repository validation remain independent from application deployment.

**Tech Stack:** Java 21, Spring Boot 4.1, SLF4J/Logback MDC, Fluent Bit 5.0.2, Logstash/Elasticsearch/Kibana 9.4.3, Kubernetes/Kustomize, Bash, Gradle

## Global Constraints

- Work only on `feat/#625-elk-application-logs`, created from `origin/develop` for issue #625.
- Collect `user-service`, `product-service`, `order-service`, `payment-service`, `admin-service`, `ai-service`, `settlement-service`, and `notification-service`; exclude `config`, `discovery`, API Gateway general logs, init containers, and infrastructure logs.
- `notification-service` receives logging configuration and allowlist support only; do not add its Kubernetes Deployment, Service, routing, or Secret resources.
- Preserve `gateway-access-*` with `gateway-access-14d`; application logs use `application-logs-*` with `application-logs-7d`, one shard, and zero replicas.
- HTTP/SSE Servlet requests use MDC `requestId`; Kafka, gRPC, Redis Pub/Sub, and settlement CronJob correlation remain out of scope.
- Do not log original exception messages, validation values, request/response bodies, stack traces, Authorization, Cookie, password, secret, API key, or token values.
- Keep Elasticsearch and Kibana private ClusterIP services; do not add NodePort, Ingress, PVC expansion, TLS, or authentication changes.
- Keep existing local-profile DEBUG settings, but set the default root logging level to `INFO`.
- Follow the repository commit format `type: 변경 대상 작업 요약` without parenthesized scopes.

---

### Task 1: Common Servlet Request ID MDC

**Files:**
- Modify: `common-module/build.gradle`
- Create: `common-module/src/main/java/com/prompthub/common/logging/RequestIdMdcFilter.java`
- Create: `common-module/src/main/java/com/prompthub/common/logging/RequestIdMdcAutoConfiguration.java`
- Create: `common-module/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `common-module/src/test/java/com/prompthub/common/logging/RequestIdMdcFilterTest.java`
- Create: `common-module/src/test/java/com/prompthub/common/logging/RequestIdMdcAutoConfigurationTest.java`

**Interfaces:**
- Consumes: inbound Servlet header `X-Request-Id`.
- Produces: `RequestIdMdcFilter.REQUEST_ID_HEADER`, `RequestIdMdcFilter.MDC_KEY`, and an auto-registered highest-precedence Servlet filter.
- Produces: MDC field `requestId` containing a UUID for downstream SLF4J events.

- [ ] **Step 1: Add the filter behavior tests**

Create tests using `MockHttpServletRequest`, `MockHttpServletResponse`, and an inline `FilterChain`. Cover all four contracts:

```java
class RequestIdMdcFilterTest {

    private final RequestIdMdcFilter filter = new RequestIdMdcFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 유효한_요청_ID를_MDC에_유지하고_요청_후_제거한다() throws Exception {
        String requestId = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, requestId);
        AtomicReference<String> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> captured.set(MDC.get(RequestIdMdcFilter.MDC_KEY)));

        assertThat(captured.get()).isEqualTo(requestId);
        assertThat(MDC.get(RequestIdMdcFilter.MDC_KEY)).isNull();
    }

    @Test
    void 요청_ID가_없으면_UUID를_생성한다() throws Exception {
        assertGeneratedUuid(new MockHttpServletRequest());
    }

    @Test
    void 요청_ID가_UUID가_아니면_새_UUID로_교체한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, "forged-request-id");
        assertGeneratedUuid(request);
    }

    @Test
    void 필터_체인이_실패해도_MDC를_제거한다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, UUID.randomUUID().toString());

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> {
                    throw new ServletException("failure");
                })).isInstanceOf(ServletException.class);
        assertThat(MDC.get(RequestIdMdcFilter.MDC_KEY)).isNull();
    }
}
```

Implement `assertGeneratedUuid` by capturing the MDC value inside the chain, parsing it with `UUID.fromString`, and asserting that MDC is empty after the chain.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :common-module:test --tests "com.prompthub.common.logging.RequestIdMdcFilterTest"
```

Expected: compilation fails because `RequestIdMdcFilter` does not exist.

- [ ] **Step 3: Implement the minimal UUID-validating filter**

Use this contract:

```java
public class RequestIdMdcFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = validUuidOrNew(request.getHeader(REQUEST_ID_HEADER));
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String validUuidOrNew(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return UUID.fromString(value).toString();
            } catch (IllegalArgumentException ignored) {
                // Invalid external values must not become log correlation identifiers.
            }
        }
        return UUID.randomUUID().toString();
    }
}
```

Add `spring-boot-autoconfigure`, `slf4j-api`, compile-only Servlet API, and test `spring-boot-starter-web` dependencies to `common-module/build.gradle`.

- [ ] **Step 4: Add and test Servlet-only auto-configuration**

Test with `WebApplicationContextRunner` that `requestIdMdcFilterRegistration` exists and has `Ordered.HIGHEST_PRECEDENCE`. Test with `ApplicationContextRunner` that the bean is absent outside a Servlet web application.

Implement:

```java
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestIdMdcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "requestIdMdcFilterRegistration")
    FilterRegistrationBean<RequestIdMdcFilter> requestIdMdcFilterRegistration() {
        FilterRegistrationBean<RequestIdMdcFilter> registration =
                new FilterRegistrationBean<>(new RequestIdMdcFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
```

Register the fully qualified auto-configuration class in:

```text
com.prompthub.common.logging.RequestIdMdcAutoConfiguration
```

- [ ] **Step 5: Run request ID tests and common-module tests**

Run:

```bash
./gradlew :common-module:test
```

Expected: all common-module tests pass.

- [ ] **Step 6: Commit the request ID unit**

```bash
git add common-module
git commit -m "feat: common-module HTTP 요청 ID MDC 추적 추가"
```

---

### Task 2: Eight-Service Structured JSON Logging

**Files:**
- Modify: `admin-service/src/main/resources/application.yml`
- Modify: `ai-service/src/main/resources/application.yml`
- Modify: `notification-service/src/main/resources/application.yml`
- Modify: `order-service/src/main/resources/application.yml`
- Modify: `payment-service/src/main/resources/application.yml`
- Modify: `product-service/src/main/resources/application.yml`
- Modify: `settlement-service/src/main/resources/application.yml`
- Modify: `user-service/src/main/resources/application.yml`
- Modify: `scripts/validate-k8s-manifests.sh`

**Interfaces:**
- Consumes: `spring.application.name` and MDC `requestId`.
- Produces: one-line Logstash JSON with `serviceName`, `level`, `message`, `@timestamp`, and optional `requestId`.
- Produces: an eight-file repository validation contract; `config` and `discovery` must remain outside the list.

- [ ] **Step 1: Extend the repository validation with the eight exact configs**

Before modifying service configs, add this validation list near the top of `scripts/validate-k8s-manifests.sh`:

```bash
APPLICATION_LOG_CONFIGS=(
  "admin-service/src/main/resources/application.yml"
  "ai-service/src/main/resources/application.yml"
  "notification-service/src/main/resources/application.yml"
  "order-service/src/main/resources/application.yml"
  "payment-service/src/main/resources/application.yml"
  "product-service/src/main/resources/application.yml"
  "settlement-service/src/main/resources/application.yml"
  "user-service/src/main/resources/application.yml"
)

for config in "${APPLICATION_LOG_CONFIGS[@]}"; do
  if ! grep -Fq 'console: logstash' "${ROOT_DIR}/${config}" \
    || ! grep -Fq 'serviceName: ${spring.application.name}' "${ROOT_DIR}/${config}" \
    || ! grep -Eq 'root:[[:space:]]+INFO$' "${ROOT_DIR}/${config}"; then
    echo "application structured logging contract missing: ${config}" >&2
    exit 1
  fi
done
```

Also explicitly fail if the list contains `config/src`, `discovery/src`, or an `apigateway` config.

- [ ] **Step 2: Run validation and verify RED**

Run:

```bash
bash scripts/validate-k8s-manifests.sh
```

Expected: fails on the first application config because `console: logstash` is absent.

- [ ] **Step 3: Add the common structured logging block to all eight configs**

Add this block to the shared/default YAML document of every listed service:

```yaml
logging:
  structured:
    format:
      console: logstash
    json:
      add:
        serviceName: ${spring.application.name}
  level:
    root: INFO
```

For `order-service`, place it in the default document before profile-specific documents so the existing local `com.prompthub.order` and Hibernate DEBUG settings continue to override only their own logger levels.

- [ ] **Step 4: Process all eight resource sets and rerun validation**

Run:

```bash
./gradlew \
  :admin-service:processResources \
  :ai-service:processResources \
  :notification-service:processResources \
  :order-service:processResources \
  :payment-service:processResources \
  :product-service:processResources \
  :settlement-service:processResources \
  :user-service:processResources
bash scripts/validate-k8s-manifests.sh
```

Expected: resource processing and structured logging validation pass.

- [ ] **Step 5: Commit the structured logging unit**

```bash
git add \
  admin-service/src/main/resources/application.yml \
  ai-service/src/main/resources/application.yml \
  notification-service/src/main/resources/application.yml \
  order-service/src/main/resources/application.yml \
  payment-service/src/main/resources/application.yml \
  product-service/src/main/resources/application.yml \
  settlement-service/src/main/resources/application.yml \
  user-service/src/main/resources/application.yml \
  scripts/validate-k8s-manifests.sh
git commit -m "chore: application-service 구조화 JSON 로그 설정 추가"
```

---

### Task 3: Sensitive Exception Log Hardening

**Files:**
- Modify: `admin-service/src/main/java/com/prompthub/admin/global/exception/GlobalExceptionHandler.java`
- Verify only: `ai-service/src/main/java/com/prompthub/ai/global/exception/GlobalExceptionHandler.java`
- Modify: `notification-service/src/main/java/com/prompthub/notification/global/exception/NotificationExceptionHandler.java`
- Modify: `order-service/src/main/java/com/prompthub/order/global/exception/GlobalExceptionHandler.java`
- Modify: `payment-service/src/main/java/com/prompthub/payment/presentation/PaymentExceptionHandler.java`
- Modify: `product-service/src/main/java/com/prompthub/product/exception/ProductExceptionHandler.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/global/exception/GlobalExceptionHandler.java`
- Modify: `user-service/src/main/java/com/prompthub/user/global/exception/GlobalExceptionHandler.java`
- Modify: `order-service/src/test/java/com/prompthub/order/global/exception/GlobalExceptionHandlerTest.java`
- Modify: `product-service/src/test/java/com/prompthub/product/exception/ProductExceptionHandlerTest.java`
- Create: `notification-service/src/test/java/com/prompthub/notification/global/exception/NotificationExceptionHandlerLoggingTest.java`

**Interfaces:**
- Consumes: service exception handlers and MDC-provided `requestId`.
- Produces: exception log messages containing only stable error code and exception type/category.
- Preserves: existing HTTP status and `ErrorResponse` payload contracts.

- [ ] **Step 1: Add notification exception logging tests**

Attach a Logback `ListAppender<ILoggingEvent>` to `NotificationExceptionHandler`. Invoke the business, invalid-input, and unexpected handlers with exception messages containing `Bearer secret-token` and `password=secret`.

Assert:

```java
assertThat(messages)
        .allMatch(message -> !message.contains("secret-token"))
        .allMatch(message -> !message.contains("password=secret"))
        .anyMatch(message -> message.contains("code="))
        .anyMatch(message -> message.contains("type="));
assertThat(events).allMatch(event -> event.getThrowableProxy() == null);
```

- [ ] **Step 2: Run the notification test and verify RED**

Run:

```bash
./gradlew :notification-service:test \
  --tests "com.prompthub.notification.global.exception.NotificationExceptionHandlerLoggingTest"
```

Expected: fails because the handler emits no security-safe code/type logs.

- [ ] **Step 3: Replace unsafe exception logging without changing responses**

Apply these exact logging rules to all handlers:

```java
log.warn("비즈니스 예외 - code={}, type={}",
        errorCode.getCode(), exception.getClass().getSimpleName());
log.warn("요청 값 검증 실패 - code={}, type={}",
        errorCode.getCode(), exception.getClass().getSimpleName());
log.error("예상하지 못한 서버 오류 - code={}, type={}",
        errorCode.getCode(), exception.getClass().getSimpleName());
```

For domain-specific handlers, keep the domain description but log only `code` and `type`. Never pass the exception object as the final logger argument. Never interpolate `exception.getMessage()`, rejected input values, request body, paths derived from exception input, supported method collections, or header values into log messages.

Remove the manual `HttpServletRequest` request-ID extraction from User, Product, and Order handlers; `requestId` now comes from MDC. Keep request parameters only where the handler needs them to construct the HTTP response.

Add `@Slf4j` to `NotificationExceptionHandler` and `PaymentExceptionHandler`. The AI handler already logs category/type without original messages or throwable objects and must remain behaviorally unchanged.

- [ ] **Step 4: Update handler tests for signatures and response regression**

Update Order and Product tests to call handlers without the removed `HttpServletRequest` argument. Keep assertions on HTTP status and response body unchanged.

In the notification logging test, assert exact event levels:

```java
assertThat(events).extracting(ILoggingEvent::getLevel)
        .contains(Level.WARN, Level.ERROR);
```

- [ ] **Step 5: Run all affected service tests**

Run:

```bash
./gradlew \
  :admin-service:test \
  :ai-service:test \
  :notification-service:test \
  :order-service:test \
  :payment-service:test \
  :product-service:test \
  :settlement-service:test \
  :user-service:test
```

Expected: all tests pass and existing response contracts remain unchanged.

- [ ] **Step 6: Audit exception log arguments**

Run:

```bash
rg -n -U 'log\\.(warn|error)\\([^;]*(exception\\.getMessage\\(\\)|exception\\.getValue\\(\\)|,\\s*exception\\s*\\))' \
  admin-service/src/main/java/com/prompthub/admin/global/exception \
  ai-service/src/main/java/com/prompthub/ai/global/exception \
  notification-service/src/main/java/com/prompthub/notification/global/exception \
  order-service/src/main/java/com/prompthub/order/global/exception \
  payment-service/src/main/java/com/prompthub/payment/presentation \
  product-service/src/main/java/com/prompthub/product/exception \
  settlement-service/src/main/java/com/prompthub/settlement/global/exception \
  user-service/src/main/java/com/prompthub/user/global/exception
```

Expected: no matches. Response construction may still use `exception.getMessage()` where an existing API contract requires it; the audit applies only to logger calls.

- [ ] **Step 7: Commit the security hardening unit**

```bash
git add \
  admin-service/src/main/java/com/prompthub/admin/global/exception \
  notification-service/src/main/java/com/prompthub/notification/global/exception \
  notification-service/src/test/java/com/prompthub/notification/global/exception \
  order-service/src/main/java/com/prompthub/order/global/exception \
  order-service/src/test/java/com/prompthub/order/global/exception \
  payment-service/src/main/java/com/prompthub/payment/presentation/PaymentExceptionHandler.java \
  product-service/src/main/java/com/prompthub/product/exception \
  product-service/src/test/java/com/prompthub/product/exception \
  settlement-service/src/main/java/com/prompthub/settlement/global/exception \
  user-service/src/main/java/com/prompthub/user/global/exception
git commit -m "fix: application-service 예외 로그 민감정보 노출 방지"
```

---

### Task 4: Fluent Bit, Logstash, and Elasticsearch Application Pipeline

**Files:**
- Modify: `k8s/addons/elk/fluent-bit.yaml`
- Modify: `k8s/addons/elk/logstash.yaml`
- Modify: `scripts/validate-k8s-manifests.sh`

**Interfaces:**
- Consumes: Kubernetes CRI log envelope containing structured JSON in `log`, Kubernetes labels, and container identity.
- Produces: normalized application documents in `application-logs-%{+YYYY.MM.dd}`.
- Preserves: Gateway documents in `gateway-access-%{+YYYY.MM.dd}`.

- [ ] **Step 1: Add failing manifest contracts for application collection**

Extend the ELK `required_patterns` in `scripts/validate-k8s-manifests.sh` with:

```text
application-logs-[*]
min_age.*7d
index.lifecycle.name.*application-logs-7d
APPLICATION_LOG_CONTAINER_REGEX
user-service|product-service|order-service|payment-service|admin-service|ai-service|settlement-service|notification-service
service.*name
[REDACTED]
```

Add negative assertions that rendered ELK configuration does not include `config|discovery` in `APPLICATION_LOG_CONTAINER_REGEX`, does not expose ELK via NodePort/Ingress, and contains exactly one `storage.total_limit_size 512M` under an `[OUTPUT]` section.

- [ ] **Step 2: Run validation and verify RED**

Run:

```bash
bash scripts/validate-k8s-manifests.sh
```

Expected: fails because the application input and index are absent.

- [ ] **Step 3: Add the Fluent Bit application input**

Keep the existing Gateway input unchanged. Add:

```ini
[INPUT]
    Name                     tail
    Tag                      kube.application.*
    Path                     /var/log/containers/*_prompthub_*.log
    Exclude_Path             /var/log/containers/apigateway-*_prompthub_apigateway-*.log,/var/log/containers/*_prompthub_wait-for-*-*.log
    multiline.parser         docker, cri
    DB                       /var/lib/fluent-bit/application.db
    DB.Sync                  Normal
    Read_from_Head           Off
    Refresh_Interval         5
    Rotate_Wait              30
    Mem_Buf_Limit            10MB
    Skip_Long_Lines          On
    storage.type             filesystem
```

Add the Kubernetes metadata filter for `kube.application.*`, then an allowlist grep filter using:

```text
^(user-service|product-service|order-service|payment-service|admin-service|ai-service|settlement-service|notification-service)$
```

Store the regex in the DaemonSet environment variable `APPLICATION_LOG_CONTAINER_REGEX`. Change the HTTP output match from `kube.apigateway.*` to `kube.*`; retain authentication, infinite retry, filesystem buffering, and the single 512M output limit.

- [ ] **Step 4: Extend Logstash routing and identity validation**

Parse `[ingest][log]` into `[structured]`. Route Gateway only when container is `apigateway` and `eventType` is `GATEWAY_ACCESS`. Route application only when all values match:

```ruby
allowed = %w[
  user-service product-service order-service payment-service
  admin-service ai-service settlement-service notification-service
]
container = event.get("[ingest][kubernetes][container_name]")
labels = event.get("[ingest][kubernetes][labels]") || {}
label_name = labels["app.kubernetes.io/name"]
service_name = event.get("[structured][serviceName]")

if allowed.include?(container) && label_name == container && service_name == container
  event.set("[@metadata][log_route]", "application")
else
  event.cancel
end
```

For application events, parse `[structured][@timestamp]`, move all non-version fields to the document root, remove the original `serviceName`, and set:

```ruby
event.set("[service][name]", service_name)
```

Retain Kubernetes metadata under `[kubernetes]`. Remove ingest envelope fields only after routing and normalization.

- [ ] **Step 5: Add recursive redaction before Elasticsearch output**

Use one Ruby filter after routing. Sensitive keys match:

```ruby
/authorization|cookie|password|secret|api[_\s-]?key|token|body/i
```

Sensitive string values match explicit Authorization/Bearer, JWT, and `password|secret|api key|token|body` key-value forms. Replace the sensitive part with `[REDACTED]`. Recursively process hashes and arrays and leave numbers, booleans, and null unchanged.

The application handler changes from Task 3 are the first defense; this Logstash filter is the second defense and must run for both Gateway and application routes.

- [ ] **Step 6: Add application ILM and index mapping**

Add `application-logs-ilm-bootstrap` alongside the existing Gateway bootstrap Job:

```json
{
  "policy": {
    "phases": {
      "hot": {"actions": {}},
      "delete": {"min_age": "7d", "actions": {"delete": {}}}
    }
  }
}
```

Create `application-logs-template` for `application-logs-*` with:

```json
{
  "index.lifecycle.name": "application-logs-7d",
  "number_of_shards": 1,
  "number_of_replicas": 0
}
```

Map `service.name`, `level`, `requestId`, `logger_name`, `thread_name`, `errorCode`, Kubernetes container/pod/namespace as keyword fields and `message` as `match_only_text`. Do not change the Gateway template or `products-v1`.

- [ ] **Step 7: Render and validate the ELK pipeline**

Run:

```bash
kubectl kustomize k8s/addons/elk >/tmp/elk-application-logs-rendered.yaml
bash scripts/validate-k8s-manifests.sh
git diff --check
```

Expected: render and repository validation pass; no placeholder, NodePort, Ingress, `emptyDir`, config/discovery allowlist entry, or second 512M buffer limit exists.

- [ ] **Step 8: Commit the collection pipeline unit**

```bash
git add k8s/addons/elk/fluent-bit.yaml k8s/addons/elk/logstash.yaml scripts/validate-k8s-manifests.sh
git commit -m "feat: elk application-service 로그 수집 파이프라인 추가"
```

---

### Task 5: Kibana Search and Manual ELK Deployment

**Files:**
- Modify: `k8s/addons/elk/kibana.yaml`
- Modify: `.github/workflows/cd-selfhosted-kubernetes.yml`
- Modify: `scripts/validate-k8s-manifests.sh`
- Modify: `scripts/validate-k8s-cd-workflow.sh`

**Interfaces:**
- Consumes: `application-logs-*` and existing ELK Secrets.
- Produces: Kibana Data View ID `application-logs`, saved search ID `application-logs-search`, and manual workflow target `elk`.

- [ ] **Step 1: Add failing Kibana and CD validation contracts**

Require these rendered or workflow patterns:

```text
application-logs-saved-objects
application-logs-*
Application Logs
service.name
requestId
application-logs-kibana-bootstrap
application-logs-ilm-bootstrap
deploy-elk
kubectl apply --server-side --dry-run=server -k k8s/addons/elk
```

Require both bootstrap Jobs to be deleted with `--ignore-not-found`, recreated, and awaited. Keep the workflow manual-only and forbid image build/publish or automatic application deployment commands in the ELK job.

- [ ] **Step 2: Run validations and verify RED**

Run:

```bash
bash scripts/validate-k8s-manifests.sh
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: validation fails because Kibana saved objects and `deploy-elk` are absent.

- [ ] **Step 3: Add idempotent Kibana saved objects**

Add a ConfigMap containing two newline-delimited JSON objects:

```json
{"type":"index-pattern","id":"application-logs","attributes":{"title":"application-logs-*","name":"Application Logs","timeFieldName":"@timestamp"}}
{"type":"search","id":"application-logs-search","attributes":{"title":"Application Logs","description":"Application logs by service, level, and request ID","columns":["service.name","level","requestId","kubernetes.container_name","message"],"sort":[["@timestamp","desc"]],"kibanaSavedObjectMeta":{"searchSourceJSON":"{\"index\":\"application-logs\",\"query\":{\"language\":\"kuery\",\"query\":\"\"},\"filter\":[]}"}}}
```

Add `application-logs-kibana-bootstrap` using `curlimages/curl:8.14.1`. Wait for `/api/status`, POST the file to `/api/saved_objects/_import?overwrite=true`, and fail unless the response includes `"success":true` and `"successCount":2`. Mount only the ConfigMap file and keep the existing security context, resource limits, node selector, and control-plane toleration conventions.

- [ ] **Step 4: Restore the manual `elk` workflow target**

Add `elk` to `workflow_dispatch.inputs.target.options` and a `deploy-elk` job that:

1. Checks out the repository.
2. Requires confirmation text `DEPLOY`.
3. Verifies `kubectl`, readable `KUBECONFIG`, `elk` namespace, `logstash-http-credentials`, and `kibana-encryption`.
4. Runs both repository validation scripts.
5. Renders and performs server-side dry-run for `k8s/addons/elk`.
6. Deletes `application-logs-ilm-bootstrap` and `application-logs-kibana-bootstrap` with `--ignore-not-found`.
7. Applies the ELK kustomization server-side.
8. Restarts and waits for Logstash and Fluent Bit.
9. Waits for both application bootstrap Jobs to complete.

Do not add a Product/all rollout input; all currently deployed allowed services are active immediately.

- [ ] **Step 5: Validate rendered objects and workflow**

Run:

```bash
kubectl kustomize k8s/addons/elk >/tmp/elk-application-logs-rendered.yaml
bash scripts/validate-k8s-manifests.sh
bash scripts/validate-k8s-cd-workflow.sh
git diff --check
```

Expected: all validation passes and the rendered Kibana service remains `ClusterIP`.

- [ ] **Step 6: Commit the search and deployment unit**

```bash
git add \
  k8s/addons/elk/kibana.yaml \
  .github/workflows/cd-selfhosted-kubernetes.yml \
  scripts/validate-k8s-manifests.sh \
  scripts/validate-k8s-cd-workflow.sh
git commit -m "feat: elk application 로그 검색과 수동 배포 추가"
```

---

### Task 6: Operations Documentation and Final Verification

**Files:**
- Modify: `k8s/README.md`
- Modify: `k8s/addons/elk/README.md`
- Modify: `k8s/addons/elk/trouble-shooting/12-gateway-only-scope-and-source-sync.md`

**Interfaces:**
- Consumes: completed logging, pipeline, ILM, Kibana, and manual deployment contracts.
- Produces: operator instructions for deployment, search, redaction verification, capacity measurement, and rollback diagnosis.

- [ ] **Step 1: Update the operator documentation**

Document:

- Gateway/Application → Fluent Bit → Logstash → separate Elasticsearch indices → Kibana.
- The exact eight-service allowlist and the fact that notification-service emits no Kubernetes logs until separately deployed.
- `service.name`, `level`, `requestId`, and Kubernetes field searches.
- 14-day Gateway versus 7-day application retention.
- Manual `elk` workflow prerequisites and both bootstrap Job checks.
- No public ELK port exposure.
- Kafka/gRPC/Redis/batch correlation exclusions.

Replace the Gateway-only troubleshooting conclusion with a scope matrix that distinguishes Gateway access, currently deployed seven-service logs, notification readiness, and excluded platform logs.

- [ ] **Step 2: Add executable post-deployment verification commands**

Include commands that operators run only after explicitly choosing the expected cluster context:

```bash
kubectl -n elk get pods,jobs,pvc -o wide
kubectl -n elk logs daemonset/fluent-bit --since=10m
kubectl -n elk logs deployment/logstash --since=10m
curl -fsS 'http://127.0.0.1:19200/_cat/indices/gateway-access-*,application-logs-*?v&s=index'
curl -fsS 'http://127.0.0.1:19200/_ilm/policy/application-logs-7d?pretty'
curl -fsS 'http://127.0.0.1:19200/application-logs-*/_search?q=service.name:product-service&size=1&pretty'
```

Document a same-request check using the response `X-Request-Id`, a settlement CronJob search without `requestId`, and negative searches for known synthetic `Bearer`, password, secret, API key, Cookie, and body marker values.

Document capacity calculation:

```text
projected_7d_bytes = largest_complete_application_day_store_size * 7
```

Record that exceeding the agreed safety margin requires a follow-up issue for log level, retention, or storage changes; this implementation does not resize the 10Gi PVC.

- [ ] **Step 3: Run focused and full verification**

Run:

```bash
./gradlew :common-module:test
./gradlew \
  :admin-service:test \
  :ai-service:test \
  :notification-service:test \
  :order-service:test \
  :payment-service:test \
  :product-service:test \
  :settlement-service:test \
  :user-service:test
bash scripts/validate-k8s-manifests.sh
bash scripts/validate-k8s-cd-workflow.sh
kubectl kustomize k8s/addons/elk >/tmp/elk-application-logs-rendered.yaml
git diff --check
git status --short
```

Expected: every test and validation command passes. Do not apply to a live cluster as part of local implementation; live deployment requires a separately authorized manual workflow run.

- [ ] **Step 4: Review scope and secrets**

Run:

```bash
git diff origin/develop...HEAD --stat
git diff origin/develop...HEAD -- \
  common-module \
  admin-service ai-service notification-service order-service \
  payment-service product-service settlement-service user-service \
  k8s/addons/elk k8s/README.md scripts .github/workflows/cd-selfhosted-kubernetes.yml
git diff origin/develop...HEAD | rg -n 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|api[_-]?key\\s*[:=]|password\\s*[:=]|Bearer [A-Za-z0-9]'
```

Expected: only issue #625 files are present and no real credential or private key value is added. Example redaction fixtures must use unmistakably synthetic values.

- [ ] **Step 5: Commit documentation**

```bash
git add \
  k8s/README.md \
  k8s/addons/elk/README.md \
  k8s/addons/elk/trouble-shooting/12-gateway-only-scope-and-source-sync.md
git commit -m "docs: elk application 로그 운영 검증 절차 추가"
```

- [ ] **Step 6: Record final evidence**

Capture the exact passing commands, test counts, skipped live-cluster checks, branch name, and commit list for the final handoff. If any test cannot run because of an unavailable external service or container runtime, report the exact failure and do not claim the feature is complete.
