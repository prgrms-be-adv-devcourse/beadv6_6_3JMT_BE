# Kibana에서 Data Views 메뉴를 찾을 수 없는 문제

## 증상

Kibana Management 메뉴에는 다음 항목만 보이고 `Data Views`가 바로 표시되지 않는다.

```text
Dev Tools
Workflows
Integrations
Fleet
Stack Monitoring
Stack Management
Streams
```

## 원인

Kibana 9의 탐색 구조에서는 `Data Views`가 Management 최상위 메뉴가 아니라
`Stack Management` 내부의 Kibana 설정 항목으로 제공된다.

## 해결

왼쪽 메뉴에서 다음 순서로 이동한다.

```text
Management
→ Stack Management
→ Kibana
→ Data Views
```

직접 URL로 이동할 수도 있다.

```text
http://127.0.0.1:5601/app/management/kibana/dataViews
```

Gateway Data View를 생성한다.

```text
Name: Gateway Access Logs
Index pattern: gateway-access-*
Timestamp field: @timestamp
```

## 인덱스가 표시되지 않을 때

Elasticsearch에서 인덱스가 실제로 생성됐는지 먼저 확인한다.

```bash
kubectl -n elk port-forward service/elasticsearch 19200:9200
```

다른 터미널에서:

```bash
curl -fsS \
  'http://127.0.0.1:19200/_cat/indices/gateway-access-*?v'
```

인덱스가 없다면 Kibana 문제가 아니라 Gateway→Fluent Bit→Logstash 파이프라인 문제다.

## Discover 조회

Classic Discover로 이동한다.

```text
http://127.0.0.1:5601/app/discover
```

Data View로 `Gateway Access Logs`를 선택하고 시간 범위를 `Last 24 hours`로 설정한다.

```kql
gateway.eventType: "GATEWAY_ACCESS"
```

권장 컬럼:

```text
@timestamp
gateway.method
gateway.path
gateway.routeId
gateway.status
gateway.durationMs
gateway.requestId
```

## 정상 판정

- Data View의 index pattern이 `gateway-access-*`
- 시간 필드가 `@timestamp`
- 최근 Gateway 요청이 Discover에 표시
- `X-Request-Id`로 특정 문서를 찾을 수 있음
