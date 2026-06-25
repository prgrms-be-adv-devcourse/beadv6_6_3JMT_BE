# API Gateway → payment-service 라우팅 검증 테스트 계획 (1단계)

## 목적

JWT 없이, Gateway가 `lb://PAYMENT-SERVICE`로 올바르게 라우팅하고 경로 rewrite가 동작하는지 확인한다.

## 범위

| 검증 대상 | 포함 여부 |
|---|---|
| Eureka 디스커버리 + `lb://PAYMENT-SERVICE` 해석 | O |
| `/payment-service/v3/api-docs` → `/v3/api-docs` 경로 rewrite | O |
| `/api/v1/payments/**` 라우트 등록 여부 | O |
| JWT 인증 통과 및 결제 엔드포인트 응답 | X (2단계) |
| `X-User-Id` / `X-User-Role` 헤더 주입 | X (2단계) |

## 전제 조건

### 기동 순서

```
1. Eureka Server
2. API Gateway        (port 8000)
3. payment-service    (port 8084)
```

payment-service가 Eureka에 `PAYMENT-SERVICE`로 등록 완료된 뒤 테스트를 시작한다.

### payment-service 설정 확인

`build.gradle`에 springdoc-openapi 의존성이 있어야 `/v3/api-docs`가 활성화된다.

```groovy
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:<버전>'
```

## 테스트 명령

### 메인 테스트 — 라우팅 + 경로 rewrite + Eureka 디스커버리

```bash
curl -v http://localhost:8000/payment-service/v3/api-docs
```

### 보조 테스트 — `/api/v1/payments/**` 라우트 등록 여부

```bash
curl -o /dev/null -s -w "%{http_code}" http://localhost:8000/api/v1/payments/confirm
```

## 기대 결과

| 요청 | 기대 응답 | 의미 |
|---|---|---|
| `GET /payment-service/v3/api-docs` | **200** + Swagger JSON 본문 | Eureka 등록 OK, 라우팅 OK, 경로 rewrite OK |
| `GET /api/v1/payments/confirm` (토큰 없음) | **401** | `/api/v1/payments/**` 라우트 등록 확인 |

## 실패 시 체크리스트

| 응답 | 원인 후보 | 조치 |
|---|---|---|
| `503` / `502` | payment-service가 Eureka에 미등록 또는 응답 불가 | Eureka 대시보드에서 `PAYMENT-SERVICE` 등록 여부 확인 |
| `404` on `/payment-service/v3/api-docs` | payment-service에 springdoc-openapi 미설정 | `build.gradle` 의존성 및 `application.yaml` 확인 |
| `404` on `/api/v1/payments/**` | Gateway 라우트 설정 오류 | `apigateway/src/main/resources/application.yaml` 라우트 확인 |
| 연결 거부 | Gateway 미기동 | Gateway 프로세스 확인 |
