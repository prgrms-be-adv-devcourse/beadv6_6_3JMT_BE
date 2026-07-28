# AWS EC2에서 Gateway와 애플리케이션 ELK 로그 직접 테스트하기

이 문서는 ELK를 처음 접하는 개발자가 기존 AWS EC2 kubeadm 클러스터에 이 add-on을 배포하고, Gateway access 로그 한 건을 Kibana에서 직접 조회할 때까지 따라 하는 실습 가이드다.

## 이 실습에서 확인하는 것

로그는 다음 순서로 이동한다.

`Gateway/Application → Fluent Bit → Logstash → Elasticsearch → Kibana`

- **Gateway**는 요청이 끝날 때 `GATEWAY_ACCESS` 형식의 JSON 로그를 표준 출력에 남긴다.
- **Fluent Bit**은 Gateway와 allowlist 애플리케이션 컨테이너의 로그 파일을 하나의 HTTP output으로 Logstash에 전송한다.
- **Logstash**는 JSON을 파싱해 Gateway access와 allowlist 애플리케이션 로그를 분기하고 민감 값을 마스킹한다.
- **Elasticsearch**는 `gateway-access-YYYY.MM.dd`를 14일, `application-logs-YYYY.MM.dd`를 7일 보관한다.
- **Kibana**는 Elasticsearch에 저장된 로그를 검색하는 화면을 제공한다.

애플리케이션 allowlist는 `user-service`, `product-service`, `order-service`, `payment-service`, `admin-service`, `ai-service`, `settlement-service`, `notification-service`다. `config`, `discovery`, `apigateway`, init container, `kube-system`, `elk`는 application index 대상이 아니다. Servlet 서비스는 `X-Request-Id`를 MDC `requestId`로 기록하고, 없으면 UUID를 생성한다. Gateway와 Settlement CronJob은 이 Servlet 필터의 대상이 아니다.

## 시작 전 안전 원칙

- 저장소는 Control Plane의 `/home/ubuntu/prompthub`에 위치한다고 가정한다.
- 실제 Secret은 Git 밖의 `/home/ubuntu/prompthub-secrets/elk-secret.yaml`에 둔다.
- EC2 Security Group에 `5601`, `9200`, `8000` 포트를 열지 않는다.
- Kibana와 Elasticsearch에는 `kubectl port-forward`와 SSH tunnel로만 접속한다.
- 기존 Elasticsearch가 `emptyDir`를 사용하고 보존 대상 인덱스를 가지고 있다면 삭제하거나 새 구성을 적용하지 않는다.
- 이 실습에서는 PVC, PV, `/var/lib/prompthub/elasticsearch`를 삭제하지 않는다.
- 터미널의 명령 블록만 실행한다. 예시 출력이나 YAML 일부를 Bash 프롬프트에 붙여 넣지 않는다.

문제가 생기면 맨 아래 [트러블슈팅 빠른 찾기](#트러블슈팅-빠른-찾기)를 먼저 확인한다.

## 준비 환경

- Control Plane과 Worker가 각각 한 대 이상 `Ready`
- PromptHub Gateway가 `prompthub` namespace에서 실행 중
- Control Plane에서 `kubectl` 사용 가능
- 저장소가 최신 상태이며 다음 파일이 존재

```bash
cd /home/ubuntu/prompthub

ls \
  k8s/addons/elk/kustomization.yaml \
  k8s/addons/elk/elasticsearch.yaml \
  k8s/addons/elk/logstash.yaml \
  k8s/addons/elk/kibana.yaml \
  k8s/addons/elk/fluent-bit.yaml \
  k8s/templates/elk-secrets.example.yaml
```

모든 경로가 출력되어야 한다. Secret 예시 파일이 없다면 [02. ELK Secret 예시 파일을 찾을 수 없는 문제](trouble-shooting/02-elk-secret-template-not-found.md)를 확인한다.

---

## 1. 클러스터와 노드 상태 확인

현재 터미널이 테스트할 클러스터를 바라보는지 먼저 확인한다.

```bash
kubectl config current-context
kubectl cluster-info
kubectl get nodes -o wide
kubectl get nodes -L prompthub.io/node-pool
kubectl get storageclass local-storage
```

정상 출력 예시는 다음과 같다. IP, AGE, VERSION은 환경마다 다르다.

```text
NAME                STATUS   ROLES           NODE-POOL
k8s-control-plane   Ready    control-plane   control-stateful
k8s-worker          Ready    <none>          application

NAME            PROVISIONER                    RECLAIMPOLICY   VOLUMEBINDINGMODE
local-storage   kubernetes.io/no-provisioner   Retain          WaitForFirstConsumer
```

### 통과 기준

- 사용할 context가 의도한 kubeadm 클러스터다.
- 모든 노드가 `Ready`다.
- Control Plane에 `prompthub.io/node-pool=control-stateful` label이 있다.
- Worker에 `prompthub.io/node-pool=application` label이 있다.
- `local-storage` StorageClass가 존재한다.

하나라도 다르면 적용하지 않는다. 특히 label이 없으면 Elasticsearch와 Fluent Bit이 원하는 노드에 배치되지 않는다.

관련 문서: [12. 애플리케이션 로그 범위와 원본 동기화](trouble-shooting/12-gateway-only-scope-and-source-sync.md)

---

## 2. 기존 Elasticsearch 안전 게이트 확인

기존 리소스와 데이터 저장 방식을 조회한다.

```bash
kubectl -n elk get deployment,statefulset,service,pvc 2>/dev/null || true

kubectl -n elk get deployment elasticsearch \
  -o jsonpath='{range .spec.template.spec.volumes[*]}{.name}{" => emptyDir="}{.emptyDir}{" pvc="}{.persistentVolumeClaim.claimName}{" hostPath="}{.hostPath.path}{"\n"}{end}' \
  2>/dev/null || true

kubectl -n elk get deployment elasticsearch \
  -o jsonpath='{range .spec.template.spec.containers[*].volumeMounts[*]}{.name}{" => "}{.mountPath}{"\n"}{end}' \
  2>/dev/null || true
```

다음 두 상태 중 하나여야 한다.

```text
새 설치: deployment/elasticsearch가 없고 조회 결과가 비어 있음
전환 완료 상태: statefulset/elasticsearch가 있고 PVC elasticsearch-data를 사용함
```

아래 결과가 나오면 **여기서 중단**한다.

```text
elasticsearch-data => emptyDir={}
```

`emptyDir` Pod를 삭제하면 `products-v1`을 포함한 기존 인덱스가 사라질 수 있다. 이 경우 snapshot 또는 검증된 export·restore와 복구 시험을 먼저 완료해야 한다. 이 문서의 일반 설치 과정에서는 기존 Deployment를 삭제하지 않는다.

### 통과 기준

- 새 설치이거나, 기존 Elasticsearch 데이터의 백업·복구가 이미 검증된 승인된 전환 상태다.
- `deployment/elasticsearch`와 `statefulset/elasticsearch`가 동시에 Service selector에 잡히지 않는다.

중단 조건이면 [01. 기존 emptyDir Elasticsearch 전환](trouble-shooting/01-existing-emptydir-elasticsearch-migration.md)을 따른다.

---

## 3. Control Plane 저장소와 커널 값 준비

다음 명령은 **Control Plane EC2**에서 실행한다.

```bash
sudo install -d -m 0750 /var/lib/prompthub/elasticsearch
sudo chown 1000:1000 /var/lib/prompthub/elasticsearch

sudo sysctl -w vm.max_map_count=1048576
sudo sh -c 'printf "vm.max_map_count=1048576\\n" > /etc/sysctl.d/99-prompthub-elasticsearch.conf'
sudo sysctl --system

stat -c '%U:%G %a %n' /var/lib/prompthub/elasticsearch
sysctl vm.max_map_count
free -h
df -h /var/lib/prompthub/elasticsearch
```

정상 확인 예시는 다음과 같다.

```text
ubuntu:ubuntu 750 /var/lib/prompthub/elasticsearch
vm.max_map_count = 1048576
```

사용자 이름은 EC2 환경에 따라 다를 수 있다. 숫자 소유자 자체를 확인하려면 다음을 실행한다.

```bash
stat -c '%u:%g %a %n' /var/lib/prompthub/elasticsearch
```

```text
1000:1000 750 /var/lib/prompthub/elasticsearch
```

Control Plane에는 OS와 기존 PostgreSQL·Redis를 제외하고 ELK requests를 수용할 **최소 1.5GiB 이상의 여유 메모리**가 필요하다. 여유가 없으면 EC2 크기를 먼저 조정한다.

### 통과 기준

- 경로가 존재하며 숫자 소유자가 `1000:1000`, mode가 `750`이다.
- `vm.max_map_count`가 `1048576` 이상이다.
- 디스크와 메모리 여유가 충분하다.

초기화 Pod가 커널 값 때문에 실패하거나 Elasticsearch가 Ready가 되지 않으면 [03. Elasticsearch Ready 확인](trouble-shooting/03-elasticsearch-ondelete-rollout-status.md)을 확인한다.

---

## 4. Git 밖에서 ELK Secret 만들기

namespace와 Secret 파일 디렉터리를 준비한다.

```bash
cd /home/ubuntu/prompthub

kubectl create namespace elk \
  --dry-run=client \
  -o yaml |
  kubectl apply -f -

install -d -m 0700 /home/ubuntu/prompthub-secrets

cp \
  k8s/templates/elk-secrets.example.yaml \
  /home/ubuntu/prompthub-secrets/elk-secret.yaml

chmod 600 /home/ubuntu/prompthub-secrets/elk-secret.yaml
```

강한 임의 값 네 개를 생성한다. 출력된 값은 Secret 파일에만 넣고 채팅, 이슈, Git에 남기지 않는다.

```bash
openssl rand -hex 32
openssl rand -hex 32
openssl rand -hex 32
openssl rand -hex 32
```

파일을 연다.

```bash
vi /home/ubuntu/prompthub-secrets/elk-secret.yaml
```

다음을 교체한다.

- `example-logstash-http-password`: 첫 번째 임의 값
- Kibana의 `replace-with-32-or-more-random-characters` 세 개: 나머지 임의 값 세 개
- `example-fluent-bit`은 운영 식별이 가능한 사용자 이름으로 변경 가능

placeholder와 권한을 확인한 뒤 적용한다.

```bash
if grep -Eq 'example-|replace-with' /home/ubuntu/prompthub-secrets/elk-secret.yaml; then
  echo 'STOP: Secret placeholder가 남아 있습니다.'
else
  echo 'OK: Secret placeholder가 없습니다.'
fi

stat -c '%a %n' /home/ubuntu/prompthub-secrets/elk-secret.yaml

kubectl apply \
  --dry-run=client \
  -f /home/ubuntu/prompthub-secrets/elk-secret.yaml

kubectl apply \
  -f /home/ubuntu/prompthub-secrets/elk-secret.yaml

kubectl -n elk get secret \
  logstash-http-credentials \
  kibana-encryption
```

Secret 값 자체는 출력하지 않는다.

### 통과 기준

- Secret 파일 mode가 `600`이다.
- placeholder가 남아 있지 않다.
- `logstash-http-credentials`, `kibana-encryption` 두 객체가 존재한다.
- Secret 파일은 `/home/ubuntu/prompthub` Git 작업 트리 밖에 있다.

파일을 찾을 수 없으면 [02. ELK Secret 예시 파일 문제](trouble-shooting/02-elk-secret-template-not-found.md), YAML 내용을 Bash 명령으로 실행했다면 [11. 터미널 출력 붙여넣기 문제](trouble-shooting/11-terminal-output-pasted-as-command.md)를 확인한다.

---

## 5. 렌더와 dry-run 후 실제 적용

저장소 루트에서 실행한다.

```bash
cd /home/ubuntu/prompthub

kubectl kustomize k8s/addons/elk > /tmp/prompthub-elk-rendered.yaml

kubectl apply \
  --dry-run=client \
  -k k8s/addons/elk

kubectl apply \
  --server-side \
  --dry-run=server \
  -k k8s/addons/elk
```

두 dry-run이 실패하지 않은 경우에만 실제 적용한다.

```bash
kubectl apply -k k8s/addons/elk

kubectl -n elk rollout restart deployment/logstash
kubectl -n elk rollout restart daemonset/fluent-bit
```

Logstash와 Fluent Bit은 ConfigMap 파일을 `subPath`로 mount한다. 기존 설치에 ConfigMap만 변경하면 실행 중인 Pod가 새 파일을 읽지 않으므로, 위 restart를 생략하지 않는다. 새 설치에서 실행해도 동일한 설정으로 새 Pod가 생성된다.

기존에 client-side apply로 관리하던 리소스는 server-side dry-run에서 다음과 같은 경고가 나올 수 있다.

```text
failed to migrate kubectl.kubernetes.io/last-applied-configuration
```

출력에 `This is non-fatal`과 `serverside-applied (server dry run)`이 있고 다른 오류가 없다면 dry-run 자체는 통과한 것이다. 실제 적용은 위의 client-side `kubectl apply -k`를 사용한다.

### 통과 기준

- Kustomize 렌더 파일이 생성된다.
- client dry-run과 server dry-run에 치명적 오류가 없다.
- 실제 apply 결과가 `created`, `configured`, `unchanged` 중 하나다.

경고의 의미와 처리 방법은 [05. Server-Side Apply 충돌 경고](trouble-shooting/05-server-side-apply-field-manager-warning.md)를 확인한다.

---

## 6. 리소스별 Ready 확인

Elasticsearch StatefulSet은 `OnDelete` 전략이므로 `rollout status`가 아니라 Pod Ready 조건을 기다린다.

```bash
kubectl -n elk wait \
  --for=create \
  pod/elasticsearch-0 \
  --timeout=5m

kubectl -n elk wait \
  --for=condition=Ready \
  pod/elasticsearch-0 \
  --timeout=10m

kubectl -n elk rollout status \
  deployment/logstash \
  --timeout=10m

kubectl -n elk rollout status \
  deployment/kibana \
  --timeout=15m

kubectl -n elk rollout status \
  daemonset/fluent-bit \
  --timeout=10m

kubectl -n elk wait \
  --for=condition=complete \
  job/gateway-access-ilm-bootstrap \
  --timeout=5m
```

전체 상태도 확인한다.

```bash
kubectl -n elk get pods,pvc,service,job -o wide
kubectl get pv elasticsearch-local-pv
```

정상 상태의 핵심은 다음과 같다.

```text
elasticsearch-0                 1/1   Running
fluent-bit-...                  1/1   Running
kibana-...                      1/1   Running
logstash-...                    1/1   Running
gateway-access-ilm-bootstrap    0/1   Completed

elasticsearch-data       Bound
elasticsearch-local-pv   Bound
```

Job Pod는 완료 후 TTL에 의해 사라질 수 있다. Job 자체가 이미 정리된 경우 11단계에서 ILM policy와 index template을 직접 확인한다.

### 통과 기준

- Elasticsearch, Logstash, Kibana, Fluent Bit이 모두 Ready다.
- Fluent Bit의 현재 Pod 재시작 횟수가 `0`이다.
- ILM bootstrap Job이 `Complete`다.
- PVC와 PV가 모두 `Bound`다.

관련 문서:

- [03. Elasticsearch rollout status 대신 Ready 확인](trouble-shooting/03-elasticsearch-ondelete-rollout-status.md)
- [04. Fluent Bit storage limit CrashLoopBackOff](trouble-shooting/04-fluent-bit-storage-limit-crashloop.md)

---

## 7. Gateway에 401 테스트 요청 만들기

Control Plane의 첫 번째 SSH 터미널에서 Gateway port-forward를 실행하고 유지한다.

```bash
kubectl -n prompthub port-forward \
  service/apigateway \
  18000:8000
```

다른 SSH 터미널에서 인증 없이 존재하지 않는 테스트 경로를 호출한다.

```bash
curl -i \
  http://127.0.0.1:18000/elk-logstash-fix-test |
  tee /tmp/gateway-401-response.txt

grep -i '^X-Request-Id:' /tmp/gateway-401-response.txt
```

정상 예시는 다음과 같다.

```text
HTTP/1.1 401 Unauthorized
X-Request-Id: 00000000-0000-0000-0000-000000000000
```

실제 `X-Request-Id` 값을 메모한다. 이후 Elasticsearch와 Kibana에서 같은 값을 찾는다.

### 통과 기준

- HTTP 상태가 `401`이다.
- 응답에 `X-Request-Id`가 있다.
- Gateway Pod 로그에 같은 요청의 `GATEWAY_ACCESS` JSON 한 건이 생성된다.

Gateway Pod의 원본 로그는 다음으로 확인할 수 있다.

```bash
kubectl -n prompthub logs \
  deployment/apigateway \
  --since=5m |
  grep 'GATEWAY_ACCESS'
```

애플리케이션 로그가 보이지 않으면 [12. 애플리케이션 로그 범위와 원본 동기화](trouble-shooting/12-gateway-only-scope-and-source-sync.md)를 확인한다.

---

## 8. 수집 파이프라인과 Elasticsearch 인덱스 확인

먼저 Fluent Bit이 Logstash로 보낸 결과를 확인한다.

```bash
kubectl -n elk logs \
  daemonset/fluent-bit \
  --since=10m |
  tail -n 100
```

정상 전송 시 다음 형태가 보인다.

```text
[output:http:http.0] logstash.elk.svc.cluster.local:8000, HTTP status=200
ok
```

Logstash에 새 codec 또는 파싱 오류가 없는지 확인한다.

```bash
kubectl -n elk logs \
  deployment/logstash \
  --since=10m |
  grep -Ei 'error|exception|codec|parse|failed' || true
```

정상이면 새로운 오류가 출력되지 않는다. Elasticsearch를 로컬 포트로 연결한다.

```bash
kubectl -n elk port-forward \
  service/elasticsearch \
  19200:9200
```

다른 SSH 터미널에서 상태와 인덱스를 확인한다.

```bash
curl -fsS \
  'http://127.0.0.1:19200/_cluster/health?pretty'

curl -fsS \
  'http://127.0.0.1:19200/_cat/indices/gateway-access-*?v'
```

단일 Elasticsearch 노드에서는 replica 때문에 cluster가 `yellow`일 수 있다. `timed_out`이 `false`이고 `unassigned_primary_shards`가 `0`이면 이 실습을 진행할 수 있다.

7단계에서 기록한 실제 요청 ID로 검색한다.

```bash
REQUEST_ID='여기에-X-Request-Id-값을-입력'

curl -fsS \
  -H 'Content-Type: application/json' \
  'http://127.0.0.1:19200/gateway-access-*/_search?pretty' \
  -d "{
    \"size\": 10,
    \"sort\": [
      {\"@timestamp\": \"desc\"}
    ],
    \"query\": {
      \"term\": {
        \"gateway.requestId.keyword\": \"${REQUEST_ID}\"
      }
    }
  }"
```

### 통과 기준

- Fluent Bit 로그에 Logstash `HTTP status=200`이 있다.
- Logstash에 새 codec·JSON parse 오류가 없다.
- `gateway-access-YYYY.MM.dd` 인덱스가 존재한다.
- 검색 결과의 `hits.total.value`가 `1` 이상이다.
- 응답의 `X-Request-Id`와 `_source.gateway.requestId`가 같다.

HTTP 200인데 인덱스가 없으면 [06. HTTP 200인데 Gateway 인덱스가 없는 문제](trouble-shooting/06-http-200-but-no-gateway-index.md), 이벤트가 Logstash에서 삭제되면 [07. Logstash additional_codecs 문제](trouble-shooting/07-logstash-additional-codecs-event-drop.md)를 확인한다.

---

## 9. Mac에서 SSH tunnel로 Kibana 접속

Control Plane SSH 터미널에서 Kibana port-forward를 실행하고 유지한다.

```bash
kubectl -n elk port-forward \
  --address 127.0.0.1 \
  service/kibana \
  5601:5601
```

Mac의 새 터미널에서 현재 사용 중인 PEM과 EC2 주소로 SSH tunnel을 연다.

```bash
chmod 400 /path/to/your-key.pem

ssh \
  -i /path/to/your-key.pem \
  -N \
  -L 5601:127.0.0.1:5601 \
  ubuntu@EC2_PUBLIC_IP
```

두 터미널을 유지한 채 Mac 브라우저에서 접속한다.

```text
http://127.0.0.1:5601
```

Mac에서 상태를 확인하려면 다음을 실행한다.

```bash
curl -fsS http://127.0.0.1:5601/api/status
```

### 통과 기준

- `http://127.0.0.1:5601`에서 Kibana 화면이 열린다.
- EC2 Security Group에 `5601`, `9200`, `8000` inbound rule을 추가하지 않았다.

접속되지 않으면 [10. Private Kibana SSH tunnel](trouble-shooting/10-private-kibana-ssh-tunnel.md)을 확인한다.

---

## 10. Data View를 만들고 Kibana에서 로그 조회

### 10-1. Data View 만들기

Kibana 왼쪽 메뉴에서 `Management → Stack Management`로 들어간 뒤 Kibana 영역의 `Data Views`를 연다. 메뉴가 보이지 않으면 브라우저 주소에 다음 경로를 직접 입력한다.

```text
http://127.0.0.1:5601/app/management/kibana/dataViews
```

`Create data view`를 누르고 다음 값을 입력한다.

```text
Name: Gateway Access Logs
Index pattern: gateway-access-*
Timestamp field: @timestamp
```

`Save data view to Kibana`를 누른다.

### 10-2. Classic Discover로 조회

1. 왼쪽 메뉴에서 `Discover`를 연다.
2. 필요하면 오른쪽 위의 `Switch to Classic`을 누른다.
3. Data View로 `Gateway Access Logs`를 선택한다.
4. 시간 범위를 `Last 24 hours` 또는 테스트 요청 시각을 포함하도록 설정한다.
5. KQL 검색창에 다음을 입력한다.

```text
gateway.eventType : "GATEWAY_ACCESS"
```

특정 요청만 찾으려면 다음을 사용한다.

```text
gateway.requestId : "7단계에서-기록한-X-Request-Id"
```

표시할 필드로 다음을 추가하면 요청 흐름을 읽기 쉽다.

```text
@timestamp
gateway.requestId
gateway.method
gateway.path
gateway.routeId
gateway.status
gateway.durationMs
gateway.authenticated
```

### 10-3. ES|QL로 같은 문서 조회

ES|QL 화면에서는 기본 `logs*` 쿼리를 지우고 다음을 실행한다.

```text
FROM gateway-access-*
| WHERE gateway.eventType == "GATEWAY_ACCESS"
| SORT @timestamp DESC
| LIMIT 100
```

특정 요청 ID 조회는 다음과 같다.

```text
FROM gateway-access-*
| WHERE gateway.requestId == "7단계에서-기록한-X-Request-Id"
| SORT @timestamp DESC
| LIMIT 10
```

### 통과 기준

- `Gateway Access Logs` Data View가 생성된다.
- Classic Discover와 ES|QL에서 같은 `gateway.requestId` 문서가 보인다.
- `_source.gateway.status`가 테스트 응답의 `401`과 일치한다.

Data Views 메뉴가 없으면 [08. Kibana Data Views 탐색](trouble-shooting/08-kibana-data-view-navigation.md), `Unknown column [@timestamp]`가 나오면 [09. ES|QL timestamp 오류](trouble-shooting/09-kibana-esql-unknown-timestamp.md)를 확인한다.

---

## 11. 14일 ILM과 Product 인덱스 비적용 확인

8단계의 Elasticsearch port-forward가 실행 중인 상태에서 확인한다.

```bash
curl -fsS \
  'http://127.0.0.1:19200/_ilm/policy/gateway-access-14d?pretty'

curl -fsS \
  'http://127.0.0.1:19200/_index_template/gateway-access-template?pretty'

curl -fsS \
  'http://127.0.0.1:19200/gateway-access-*/_settings?filter_path=*.settings.index.lifecycle.name&pretty'
```

정상 결과에는 다음 계약이 포함된다.

```text
policy delete min_age: 14d
index_patterns: gateway-access-*
index.lifecycle.name: gateway-access-14d
```

Product Service 인덱스에는 Gateway ILM이 적용되지 않았는지 확인한다.

```bash
curl -fsS \
  'http://127.0.0.1:19200/products-v1/_settings?filter_path=*.settings.index.lifecycle.name&pretty'
```

정상이라면 `gateway-access-14d`가 출력되지 않는다. `products-v1`이 아직 생성되지 않은 새 환경에서는 `index_not_found_exception`이 나올 수 있으며, 이는 Gateway ILM이 Product 인덱스에 적용됐다는 뜻이 아니다.

### 통과 기준

- `gateway-access-*`에 `gateway-access-14d`가 적용되어 있다.
- policy delete phase의 `min_age`가 `14d`다.
- `products-v1`에는 `gateway-access-14d`가 없다.

정책이 다르거나 서비스 내부 로그가 보이지 않으면 [12. 애플리케이션 로그 범위와 원본 동기화](trouble-shooting/12-gateway-only-scope-and-source-sync.md)를 확인한다.

---

## 실습 완료 체크리스트

- [ ] 두 노드가 Ready이고 node-pool label이 올바르다.
- [ ] 기존 `emptyDir` Elasticsearch 데이터 손실 위험을 확인했다.
- [ ] Elasticsearch PV 경로와 `vm.max_map_count`를 준비했다.
- [ ] 실제 Secret은 Git 밖에 mode `600`으로 저장했다.
- [ ] render, client dry-run, server dry-run, 실제 apply가 성공했다.
- [ ] Elasticsearch, Logstash, Kibana, Fluent Bit이 Ready다.
- [ ] ILM bootstrap Job이 완료됐다.
- [ ] 401 응답의 `X-Request-Id`를 기록했다.
- [ ] Fluent Bit에서 Logstash HTTP 200을 확인했다.
- [ ] Elasticsearch에서 같은 `gateway.requestId` 문서를 찾았다.
- [ ] SSH tunnel로 Kibana에 접속했다.
- [ ] Classic Discover와 ES|QL에서 같은 로그를 확인했다.
- [ ] Gateway 인덱스에만 14일 ILM이 적용된 것을 확인했다.
- [ ] EC2 Security Group에 ELK 포트를 공개하지 않았다.

## 트러블슈팅 빠른 찾기

| 번호 | 증상 또는 확인할 주제 | 문서 |
|---:|---|---|
| 01 | 기존 Elasticsearch가 Deployment와 `emptyDir`를 사용함 | [기존 emptyDir Elasticsearch 전환](trouble-shooting/01-existing-emptydir-elasticsearch-migration.md) |
| 02 | `k8s/templates/elk-secrets.example.yaml`이 없음 | [ELK Secret 예시 파일 문제](trouble-shooting/02-elk-secret-template-not-found.md) |
| 03 | StatefulSet에서 `rollout status`를 사용할 수 없음 | [Elasticsearch Ready 확인](trouble-shooting/03-elasticsearch-ondelete-rollout-status.md) |
| 04 | Fluent Bit이 `storage.total_limit_size` 오류로 재시작함 | [Fluent Bit CrashLoopBackOff](trouble-shooting/04-fluent-bit-storage-limit-crashloop.md) |
| 05 | server-side dry-run에서 field manager 경고가 발생함 | [Server-Side Apply 경고](trouble-shooting/05-server-side-apply-field-manager-warning.md) |
| 06 | Fluent Bit HTTP 200인데 인덱스가 없음 | [Gateway 인덱스 미생성](trouble-shooting/06-http-200-but-no-gateway-index.md) |
| 07 | Logstash가 `application/json` 이벤트를 drop함 | [Logstash additional_codecs](trouble-shooting/07-logstash-additional-codecs-event-drop.md) |
| 08 | Kibana에서 Data Views 메뉴를 찾지 못함 | [Data Views 탐색](trouble-shooting/08-kibana-data-view-navigation.md) |
| 09 | Discover에서 `Unknown column [@timestamp]` 오류가 남 | [ES|QL timestamp 오류](trouble-shooting/09-kibana-esql-unknown-timestamp.md) |
| 10 | Mac에서 private Kibana에 접속할 수 없음 | [SSH tunnel 접속](trouble-shooting/10-private-kibana-ssh-tunnel.md) |
| 11 | 표나 YAML 출력 내용을 Bash 명령으로 실행함 | [터미널 출력 붙여넣기](trouble-shooting/11-terminal-output-pasted-as-command.md) |
| 12 | 각 서비스 내부 로그가 보이지 않거나 EC2 수정이 원본과 다름 | [애플리케이션 로그 범위와 원본 동기화](trouble-shooting/12-gateway-only-scope-and-source-sync.md) |

## 실습 범위 밖의 작업

다음 작업은 이 입문 실습에서 수행하지 않는다.

- 기존 PVC, PV 또는 `/var/lib/prompthub/elasticsearch` 삭제
- User, Product, Order, Payment 등 서비스별 Java 상세 로그 수집
- Elasticsearch security 활성화, TLS 인증서와 사용자 인증 전환
- Kibana, Elasticsearch, Logstash 포트의 외부 공개
- `products-v1` 데이터 마이그레이션이나 복구본 생성

이 작업들은 데이터 보존, 접근 제어, 저장 용량에 영향을 주므로 별도 설계와 점검 시간으로 진행한다.
