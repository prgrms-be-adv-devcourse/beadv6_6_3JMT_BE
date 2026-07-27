# 애플리케이션 로그 범위와 EC2 수정사항 원본 동기화

## 증상

Gateway 로그는 Kibana에서 확인되지만 다음 서비스의 내부 Java 로그는 검색되지 않는다.

- user-service
- product-service
- order-service
- payment-service
- admin-service
- ai-service
- settlement-service
- notification-service (workload 배포 후)

또는 EC2에서는 파이프라인이 정상인데 같은 Git 매니페스트를 다시 적용하면 기존 문제가 재발한다.

## 원인 1: 애플리케이션 로그 수집 조건 불일치

애플리케이션 이벤트는 Kubernetes container name, Kubernetes label `app.kubernetes.io/name`, Spring JSON 로그의 `serviceName`이 모두 allowlist와 일치해야 `application-logs-*`에 저장된다. 이 검증은 init container·platform 로그·잘못 라벨링된 Pod가 애플리케이션 인덱스에 섞이는 것을 막는다.

`config`, `discovery`, `apigateway`, `elk`, `kube-system`은 의도적으로 수집하지 않는다. `notification-service`는 collector와 애플리케이션 설정에 포함되어 있지만 Kubernetes workload가 아직 없으면 로그가 생성되지 않는다.

```bash
kubectl -n prompthub get pod -l app.kubernetes.io/name=product-service \
  -o jsonpath='{range .items[*]}{.metadata.name}{" label="}{.metadata.labels.app\\.kubernetes\\.io/name}{" containers="}{range .spec.containers[*]}{.name}{","}{end}{"\\n"}{end}'

kubectl -n prompthub logs deployment/product-service -c product-service --tail=50
```

두 번째 명령의 JSON에 `serviceName`, `level`, `requestId`가 포함되는지 확인한다. Kibana에서는 정규화된 필드 `service.name`으로 검색한다.

## 원인 2: EC2 hotfix와 Git 원본의 차이

운영 중 다음 두 수정이 EC2 파일에 직접 적용되었다.

### Fluent Bit

```diff
 [INPUT]
     storage.type             filesystem
-    storage.total_limit_size 512M

 [OUTPUT]
+    storage.total_limit_size 512M
```

### Logstash

```diff
 input {
   http {
+    additional_codecs => {}
     codec => json {
       target => "[ingest]"
     }
   }
 }
```

Git 원본에 반영하지 않으면 이후 pull, 복사 또는 CD 적용에서 수정이 사라진다.

## 해결 1: 원본 저장소 동기화

Mac 또는 Git 작업 디렉터리에서 다음 파일에 같은 수정사항을 반영한다.

```text
k8s/addons/elk/fluent-bit.yaml
k8s/addons/elk/logstash.yaml
```

검증:

```bash
kubectl kustomize k8s/addons/elk >/tmp/elk-rendered.yaml
bash scripts/validate-k8s-manifests.sh
git diff --check
```

EC2 live 설정과 비교한다.

```bash
kubectl -n elk get configmap fluent-bit-config \
  -o jsonpath='{.data.fluent-bit\.conf}' \
  > /tmp/live-fluent-bit.conf

kubectl -n elk get configmap logstash-pipeline \
  -o jsonpath='{.data.gateway-access\.conf}' \
  > /tmp/live-logstash.conf
```

## 현재 범위의 정상 판정

Gateway와 애플리케이션 파이프라인은 다음 조건이면 정상이다.

```bash
kubectl -n elk get pods -o wide
curl -fsS \
  'http://127.0.0.1:19200/_cat/indices/gateway-access-*,application-logs-*?v'
```

- Elasticsearch, Fluent Bit, Logstash, Kibana가 Ready
- 두 ILM bootstrap Job이 Completed
- `gateway-access-YYYY.MM.dd`와 요청을 발생시킨 서비스의 `application-logs-YYYY.MM.dd` 생성
- Kibana에서 `gateway.requestId`와 같은 `requestId`를 검색 가능
- `gateway-access-*`에는 14일, `application-logs-*`에는 7일 ILM 적용
- `products-v1`에는 두 ILM 정책이 적용되지 않음

## 주의사항

`notification-service` workload가 없는 상태에서는 해당 서비스 로그가 없더라도 정상이다. workload가 배포된 뒤에는 다른 allowlist 서비스와 동일하게 수집된다.
