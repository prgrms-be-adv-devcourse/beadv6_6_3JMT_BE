# Fluent Bit HTTP 200인데 `gateway-access-*` 인덱스가 없는 문제

## 증상

Gateway는 `GATEWAY_ACCESS` JSON 로그를 정상 출력하고 Fluent Bit도 Logstash로 HTTP 200을 받는다.

```text
[output:http:http.0] logstash.elk.svc.cluster.local:8000, HTTP status=200
ok
```

그러나 Elasticsearch에는 인덱스가 없다.

```bash
curl -fsS \
  'http://127.0.0.1:19200/_cat/indices/gateway-access-*?v'
```

헤더만 출력되거나 검색 결과가 0건이다.

## 의미

HTTP 200은 Logstash HTTP input이 요청을 수신했다는 뜻이다. Logstash filter 통과와
Elasticsearch indexing 성공까지 보장하지 않는다.

## 경계별 진단

### 1. Gateway 출력

```bash
gateway_pod="$(kubectl -n prompthub get pod \
  -l app.kubernetes.io/name=apigateway \
  -o jsonpath='{.items[0].metadata.name}')"

kubectl -n prompthub logs "${gateway_pod}" \
  -c apigateway --since=5m | grep GATEWAY_ACCESS
```

### 2. Fluent Bit 입력과 출력

```bash
kubectl -n elk logs daemonset/fluent-bit --since=5m
```

다음을 모두 확인한다.

- Gateway container log 파일에 inotify watch가 추가됨
- Kubernetes API 연결 성공
- Logstash HTTP 응답 200

### 3. Logstash 파이프라인

```bash
kubectl -n elk logs deployment/logstash --since=5m

kubectl -n elk exec deployment/logstash -- \
  sed -n '1,240p' \
  /usr/share/logstash/pipeline/gateway-access.conf
```

### 4. Elasticsearch

```bash
curl -fsS 'http://127.0.0.1:19200/_cluster/health?pretty'
curl -fsS 'http://127.0.0.1:19200/_cat/indices?v'
```

## 이 사례의 원인

Gateway와 Fluent Bit은 정상이었다. Logstash HTTP input의 Content-Type별 기본 JSON codec이
이벤트를 예상 구조와 다르게 만들었고, 다음 filter가 이벤트를 모두 삭제했다.

```ruby
if ![ingest][log] {
  drop { }
}
```

구체적인 해결 방법은
[07-logstash-additional-codecs-event-drop.md](./07-logstash-additional-codecs-event-drop.md)를 따른다.

## 정상 판정

새 Gateway 요청을 보낸 뒤 다음을 확인한다.

```bash
curl -fsS \
  'http://127.0.0.1:19200/_cat/indices/gateway-access-*?v'
```

```text
gateway-access-YYYY.MM.dd
```

특정 Request ID도 검색한다.

```bash
curl -fsS -H 'Content-Type: application/json' \
  'http://127.0.0.1:19200/gateway-access-*/_search?pretty' \
  -d '{"query":{"term":{"gateway.requestId.keyword":"<X-Request-Id>"}}}'
```
