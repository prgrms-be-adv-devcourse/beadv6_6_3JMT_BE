# Gateway 로그 전용 범위와 EC2 수정사항 원본 동기화

## 증상

Gateway 로그는 Kibana에서 확인되지만 다음 서비스의 내부 Java 로그는 검색되지 않는다.

- user-service
- product-service
- order-service
- payment-service
- admin-service
- ai-service
- settlement-service
- config
- discovery

또는 EC2에서는 파이프라인이 정상인데 같은 Git 매니페스트를 다시 적용하면 기존 문제가 재발한다.

## 원인 1: 현재 add-on은 Gateway 전용

Fluent Bit input은 API Gateway 컨테이너 로그만 읽는다.

```ini
Path /var/log/containers/apigateway-*_prompthub_apigateway-*.log
```

Logstash도 `GATEWAY_ACCESS`가 아닌 이벤트를 삭제한다.

```ruby
if "_gateway_json_parse_failure" in [tags] or [gateway][eventType] != "GATEWAY_ACCESS" {
  drop { }
}
```

따라서 `gateway.routeId: "product-service"`는 Product Service로 라우팅한 Gateway 요청을
뜻할 뿐 Product Service 내부 로그가 아니다.

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

## 해결 2: 서비스 내부 로그 수집은 별도 기능으로 구현

필요 작업:

1. 모든 서비스의 console 로그를 공통 structured JSON으로 통일한다.
2. `X-Request-Id`를 서비스 MDC `requestId`에 넣는다.
3. Fluent Bit에 `prompthub` 애플리케이션용 별도 input과 DB를 추가한다.
4. init container와 민감한 시스템 로그를 제외한다.
5. Logstash에 application pipeline을 추가한다.
6. `application-logs-*` 전용 index template과 ILM을 만든다.
7. Elasticsearch 10Gi에 맞춰 하루 로그량과 보존 기간을 산정한다.
8. Authorization, Cookie, token, password, secret와 body를 수집하지 않는다.
9. Product Service 하나로 canary 검증한 뒤 서비스를 순차 확대한다.
10. Kibana에 `application-logs-*` Data View를 만든다.

## 현재 범위의 정상 판정

Gateway 전용 파이프라인은 다음 조건이면 정상이다.

```bash
kubectl -n elk get pods -o wide
curl -fsS \
  'http://127.0.0.1:19200/_cat/indices/gateway-access-*?v'
```

- Elasticsearch, Fluent Bit, Logstash, Kibana가 Ready
- ILM bootstrap Job이 Completed
- `gateway-access-YYYY.MM.dd` 생성
- Kibana에서 `gateway.requestId`로 요청 검색 가능
- `gateway-access-*`만 14일 ILM 적용
- `products-v1`에는 Gateway ILM이 적용되지 않음

## 주의사항

Gateway 파이프라인 검증 완료를 “모든 서비스 상세 로그 수집 완료”로 해석하지 않는다. 서비스 로그
확장은 로그 스키마, 보안, 보존 기간과 용량까지 포함하는 별도 변경으로 관리한다.
