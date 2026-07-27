# Logstash `additional_codecs` 우선순위로 이벤트가 삭제되는 문제

## 증상

다음 조건이 모두 성립한다.

- Gateway가 `GATEWAY_ACCESS` JSON을 출력한다.
- Fluent Bit이 해당 파일을 tail한다.
- Fluent Bit이 Logstash에서 HTTP 200을 받는다.
- Logstash Pod는 `1/1 Running`이다.
- `gateway-access-*` 인덱스는 생성되지 않는다.

Logstash 로그에는 다음 경고가 반복된다.

```text
ECS compatibility is enabled but `target` option was not specified
```

파이프라인에는 분명히 `target`이 선언되어 있다.

```ruby
codec => json {
  target => "[ingest]"
}
```

## 원인

Fluent Bit은 다음 헤더로 JSON을 전송한다.

```ini
Header Content-Type application/json
```

Logstash HTTP input은 `application/json`에 대한 기본 `additional_codecs` 매핑을 먼저 검사한다.
이 매핑이 명시한 `codec => json { target => "[ingest]" }`보다 우선하여 JSON을 이벤트 최상위에
풀어 놓는다.

결과적으로 `[ingest][log]`가 존재하지 않아 다음 조건에서 모든 이벤트가 삭제된다.

```ruby
if ![ingest][log] {
  drop { }
}
```

## 진단

ConfigMap과 컨테이너 파일이 같은지 확인한다.

```bash
kubectl -n elk get configmap logstash-pipeline \
  -o jsonpath='{.data.gateway-access\.conf}' \
  > /tmp/live-logstash.conf

kubectl -n elk exec deployment/logstash -- \
  cat /usr/share/logstash/pipeline/gateway-access.conf \
  > /tmp/container-logstash.conf

diff -u /tmp/live-logstash.conf /tmp/container-logstash.conf
```

차이가 없다면 ConfigMap mount 문제가 아니다.

## 해결

HTTP input에 빈 `additional_codecs`를 선언해 명시한 JSON codec이 사용되도록 한다.

```ruby
input {
  http {
    port => 8000
    user => "${LOGSTASH_HTTP_USERNAME}"
    password => "${LOGSTASH_HTTP_PASSWORD}"
    additional_codecs => {}
    codec => json {
      target => "[ingest]"
    }
  }
}
```

적용:

```bash
kubectl apply --dry-run=client \
  -k k8s/addons/elk

kubectl apply \
  -k k8s/addons/elk

kubectl -n elk rollout restart deployment/logstash
kubectl -n elk rollout status deployment/logstash --timeout=10m
```

## 검증

컨테이너가 새 설정을 읽었는지 확인한다.

```bash
kubectl -n elk exec deployment/logstash -- \
  sed -n '1,16p' \
  /usr/share/logstash/pipeline/gateway-access.conf
```

새 Gateway 요청을 보낸다.

```bash
kubectl -n prompthub port-forward service/apigateway 18000:8000
```

다른 터미널에서:

```bash
curl -i http://127.0.0.1:18000/elk-logstash-fix-test
```

5~10초 뒤:

```bash
curl -fsS \
  'http://127.0.0.1:19200/_cat/indices/gateway-access-*?v'
```

## 정상 판정

- 새 Logstash Pod가 `1/1 Running`
- 새 로그에서 `target option was not specified`가 반복되지 않음
- Fluent Bit HTTP 응답이 200
- `gateway-access-YYYY.MM.dd` 생성
- 응답 `X-Request-Id`와 `gateway.requestId`가 일치

## 주의사항

Fluent Bit은 tail DB에 읽은 offset을 저장한다. Logstash 수정 전에 읽은 로그가 자동으로 다시
전송된다고 가정하지 말고 수정 후 반드시 새 요청을 발생시킨다.
