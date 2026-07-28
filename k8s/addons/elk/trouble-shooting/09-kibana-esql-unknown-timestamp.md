# Kibana Discover에서 `Unknown column [@timestamp]`가 발생하는 문제

## 증상

새 Discover 화면에서 다음 기본 ES|QL 쿼리가 실행된다.

```esql
FROM logs*, -logstash*, filebeat-*
| WHERE @timestamp >= ?_tstart AND @timestamp <= ?_tend
```

오류:

```text
verification_exception
Unknown column [@timestamp]
Unable to retrieve search results
```

왼쪽에는 사용 가능한 필드가 0개로 표시된다.

## 원인

현재 Gateway 인덱스 이름은 `gateway-access-*`다. Discover 기본 ES|QL 쿼리의
`logs*`, `filebeat-*` 패턴에는 이 인덱스가 포함되지 않는다. 일치하는 인덱스가 없으므로
`@timestamp` 컬럼도 찾을 수 없다.

## 해결 방법 1: Classic Discover 사용

화면 오른쪽 위의 `Switch to Classic`을 누른다.

1. Data View에서 `Gateway Access Logs` 선택
2. 시간 범위를 `Last 24 hours`로 변경
3. KQL 실행

```kql
gateway.eventType: "GATEWAY_ACCESS"
```

## 해결 방법 2: ES|QL 쿼리 수정

현재 화면을 유지하려면 쿼리를 다음으로 바꾼다.

```esql
FROM gateway-access-*
| SORT @timestamp DESC
| LIMIT 100
```

Gateway 이벤트만 조회:

```esql
FROM gateway-access-*
| WHERE gateway.eventType == "GATEWAY_ACCESS"
| SORT @timestamp DESC
| LIMIT 100
```

필요한 컬럼만 표시:

```esql
FROM gateway-access-*
| KEEP @timestamp,
       gateway.method,
       gateway.path,
       gateway.routeId,
       gateway.status,
       gateway.durationMs,
       gateway.requestId
| SORT @timestamp DESC
| LIMIT 100
```

## 정상 판정

- 오류 없이 Gateway 문서가 출력됨
- `gateway.path`, `gateway.status`, `gateway.requestId`가 표시됨
- 테스트 응답의 `X-Request-Id`로 같은 요청을 식별할 수 있음

## 주의사항

KQL과 ES|QL은 문법이 다르다. Classic Discover에서는 KQL을 사용하고, context-aware Discover에서는
`FROM`, `WHERE`, `SORT` 형태의 ES|QL을 사용한다.
