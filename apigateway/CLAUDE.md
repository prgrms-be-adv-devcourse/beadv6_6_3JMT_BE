# API Gateway

Spring Cloud Gateway Server WebFlux 기반의 모든 외부 HTTP 요청 진입점.

- **포트**: 8000
- **기동 순서**: Config와 Discovery 다음

---

## 핵심 파일

| 파일 | 역할 |
| --- | --- |
| `src/main/resources/application.yml` | 라우트, API 버전과 Logstash JSON stdout 설정 |
| `config/SecurityConfig.java` | JWT 서명·만료 검증과 공개 경로 |
| `filter/ForwardAuthFilter.java` | 내부 authorize 확인, 역할 정책과 신뢰 헤더 주입 |
| `logging/GatewayAccessLogWebFilter.java` | 요청 ID 생성·전파와 요청 단위 access 로그 |
| `logging/GatewayAccessLogFactory.java` | 응답·라우트·인증 결과를 access 모델로 변환 |
| `logging/GatewayAccessLogWriter.java` | SLF4J key-value 구조화 로그 출력 |

---

## 요청 처리 흐름

1. 최외곽 `GatewayAccessLogWebFilter`가 비헬스 요청마다 새 UUID를 생성한다.
2. 외부 `X-Request-Id`를 교체하고 외부 `X-User-Id`, `X-User-Role`을 제거한다.
3. `SecurityConfig`가 보호 경로의 JWT 서명과 만료를 검증한다.
4. 라우트가 매칭되면 `ForwardAuthFilter`가 내부 authorize API로 계정 상태와 역할을 확인한다.
5. ACTIVE 요청에는 `X-User-Id`, `X-User-Role`을 신뢰 값으로 주입하고 `Authorization`은 제거한다.
6. 요청 종료 시 `GATEWAY_ACCESS` 이벤트를 정확히 한 건 기록한다.

Security가 먼저 반환하는 401과 라우트 미매칭 404도 최외곽 WebFilter가 기록한다.

---

## 요청 ID와 신뢰 헤더

- `X-Request-Id`: Gateway가 매 요청 새 UUID로 생성하며 다운스트림 요청과 최종 응답에 동일하게 전파한다.
- `X-User-Id`: 내부 authorize가 성공한 인증 요청에만 JWT subject를 주입한다.
- `X-User-Role`: 내부 authorize가 반환한 `BUYER`, `SELLER`, `ADMIN` 중 하나를 주입한다.
- `Authorization`: Security 검증 이후 다운스트림으로 전달하지 않는다.
- 브라우저가 응답 요청 ID를 읽을 수 있도록 `X-Request-Id`를 CORS exposed header로 제공한다.

---

## Access 로그 계약

- `eventType=GATEWAY_ACCESS`, `service=apigateway`
- `requestId`, `method`, query 없는 `path`, `routeId`
- `status`, `durationMs`, `authenticated`, 선택적 `userRole`
- `clientIp`, 선택적 `exceptionType`, Boot 최상위 `level`
- 정상 2xx·3xx는 INFO, 정상 4xx는 WARN, 정상 5xx는 ERROR
- `ErrorResponse` 예외는 내장 HTTP status와 그 status에 맞는 level을 사용하고 `exceptionType`을 유지
- 그 밖의 예외는 status 500과 ERROR
- 클라이언트 취소는 다른 failure 존재 여부와 무관하게 status 499와 WARN
- `/actuator/health/**`, `/liveness`, `/readiness`는 access 로그에서 제외

`Authorization`, Cookie, userId, query string과 요청·응답 본문은 기록하지 않는다. 일반 Gateway 로그를 포함한 stdout 전체는 Spring Boot Logstash JSON 한 줄 형식이다.

---

## 개발 규칙

- WebFlux 기반이므로 `spring-boot-starter-web`을 추가하지 않는다.
- 공개 경로는 `WhitelistPathResolver`에서 API 버전 설정과 함께 관리한다.
- Gateway가 만든 요청 ID와 신뢰 사용자 헤더를 다운스트림 계약으로 사용한다.
- 역할 부족 403은 authorize가 성공한 요청이므로 access 로그에 `authenticated=true`와 실제 역할을 유지한다.

---

## 참고

전체 요청 흐름은 `docs/architecture/spring-cloud.md`, 상세 설계는 `apigateway/docs/superpowers/specs/2026-07-23-gateway-structured-access-log-design.md`를 참조한다.
