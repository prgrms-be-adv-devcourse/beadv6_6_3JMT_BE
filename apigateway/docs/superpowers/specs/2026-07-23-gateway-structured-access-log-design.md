# API Gateway 요청 단위 구조화 로그 설계

- 작성일: 2026-07-23
- 관련 이슈: [#538 API Gateway 요청 단위 구조화 로그 및 X-Request-Id 전파](https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/issues/538)
- 대상 모듈: `apigateway`

## 1. 요약

API Gateway의 모든 콘솔 로그를 Spring Boot Logstash JSON 형식으로 통일하고, 헬스 체크를 제외한 외부 요청마다 `GATEWAY_ACCESS` 이벤트를 정확히 한 건 기록한다. 최외곽 `WebFilter`가 Spring Security와 Gateway 라우팅을 모두 감싸도록 해 Security가 먼저 반환하는 401, 미매칭 404, 예외와 클라이언트 취소까지 동일한 계약으로 기록한다.

구현은 신규 `logging` 컴포넌트에 집중한다. 기존 인증·라우팅 흐름은 유지하고, `ForwardAuthFilter`, CORS 설정, 애플리케이션 로깅 설정에 필요한 연결점만 추가한다.

## 2. 문제와 목표

현재 ELK 수집 파이프라인은 동작하지만 Gateway 로그에는 요청 ID, 라우트, 상태와 처리 시간이 공통 필드로 존재하지 않는다. 또한 `GlobalFilter`만으로는 Spring Security가 라우팅 전에 종료하는 401과 라우트가 없는 404를 빠짐없이 감쌀 수 없다.

이번 변경의 목표는 다음과 같다.

1. Gateway가 신뢰 가능한 새 요청 ID를 생성해 다운스트림 요청과 최종 응답에 전파한다.
2. 요청 종료 결과를 요청당 정확히 하나의 구조화된 access 이벤트로 남긴다.
3. 인증 결과와 역할을 access 이벤트에 기록하되 사용자 ID와 인증 비밀값은 기록하지 않는다.
4. 일반 Gateway 로그를 포함한 stdout 전체를 Logstash JSON 한 줄 형식으로 출력한다.
5. 기존 인증, 라우팅, ELK 수집과 배포 구조를 변경하지 않는다.

## 3. 비목표

다음 항목은 이번 변경 범위에 포함하지 않는다.

- Fluent Bit, Logstash, Elasticsearch, Kibana 설정 변경
- Gradle 의존성 추가 또는 버전 변경
- `SecurityConfig`, Gateway 라우트 정의, `AuthorizeClient` 동작 변경
- 공통 모듈이나 다운스트림 서비스의 로깅 방식 변경
- 요청·응답 본문 로깅
- 분산 추적 표준 또는 tracing SDK 도입
- DB, Kafka, Kubernetes 매니페스트 변경

## 4. 설계 원칙과 최소 수정 경계

### 4.1 신규 코드 중심

다음 컴포넌트를 새로 추가한다.

| 컴포넌트 | 책임 |
| --- | --- |
| `GatewayAccessLogWebFilter` | 요청 컨텍스트 확립, 요청 ID 전파, 종료 신호 관찰, access 이벤트 단일 기록 |
| `GatewayAccessLog` | access 로그 필드와 내부 로그 레벨을 보관하는 불변 record |
| `GatewayAccessLogFactory` | exchange와 종료 결과에서 로그 모델 생성 |
| `GatewayAccessLogWriter` | SLF4J fluent key-value API로 이벤트 출력 |
| `GatewayLogConstants` | 헤더명, exchange attribute key, 고정 필드값 관리 |
| `ClientIpResolver` | XFF와 remote address에서 client IP 결정 |

`@Component`와 기존 component scan을 사용하므로 애플리케이션 시작 클래스나 별도 설정 클래스는 수정하지 않는다.

### 4.2 기존 파일의 제한된 변경

| 기존 파일 | 허용하는 변경 |
| --- | --- |
| `ForwardAuthFilter.java` | Security 처리 후 다운스트림으로 전달할 헤더 정리, 인증 성공 attribute 기록, 신뢰 헤더 `set` |
| `CorsConfig.java` | `X-Request-Id`를 exposed header에 추가 |
| `application.yml` | `logging.structured.format.console: logstash` 추가 |
| `ForwardAuthFilterTest.java` | 위조 헤더·인증 attribute 회귀 사례 추가 |
| `apigateway/CLAUDE.md` | 요청 ID와 access 로그 계약 반영 |
| 구조화 로그 설계·구현 계획 | 최종 IP 검증, 종료 상태·레벨과 통합 테스트 이력 반영 |
| `docs/architecture/spring-cloud.md` | 실제 Gateway 인증·요청 흐름과 로그 단계 반영 |

기존 인증 분기, 역할 정책 판정, 라우트 정의와 인증 서버 호출 계약은 재구성하지 않는다.

## 5. 검토한 접근

### 5.1 최외곽 WebFilter와 분리된 로그 컴포넌트

선택한 접근이다. `Ordered.HIGHEST_PRECEDENCE`인 `WebFilter`가 전체 WebFlux 체인을 감싼다. 로그 모델 생성, IP 추출과 출력은 별도 클래스로 분리해 필터가 요청 생명주기 제어만 담당하게 한다.

이 방식은 Security 선행 401, 미매칭 404와 취소 신호를 한 위치에서 처리하면서 기존 Gateway 필터 체인을 유지할 수 있다.

### 5.2 GlobalFilter와 Security 핸들러 조합

`GlobalFilter`에 정상·라우팅 오류 로그를 두고 인증 진입점과 접근 거부 핸들러에 별도 로그를 추가하는 방식이다. 여러 종료 지점에서 중복 방지 로직이 필요하고 미매칭 요청 처리도 분산되므로 선택하지 않는다.

### 5.3 Micrometer Observation 확장

기존 관측 인프라에 custom convention이나 handler를 추가하는 방식이다. 장기적인 tracing 통합에는 적합하지만 이번 고정 JSON 계약, 인증 attribute와 499 취소 정책을 구현하기에는 변경 범위가 크므로 선택하지 않는다.

## 6. 요청 처리 흐름

### 6.1 헬스 체크 제외

다음 경로는 요청 ID 생성과 access 로그 기록을 모두 생략하고 기존 체인에 그대로 위임한다.

- `/actuator/health`
- `/actuator/health/**`
- `/liveness`
- `/readiness`

현재 Kubernetes probe 경로인 `/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness`는 모두 이 규칙으로 제외된다.

### 6.2 요청 컨텍스트 확립

헬스 체크가 아닌 요청에는 다음 순서로 컨텍스트를 설정한다.

1. 외부 `X-Request-Id` 값을 사용하지 않고 새 UUID를 생성한다.
2. 외부 `X-User-Id`, `X-User-Role` 헤더를 제거한다.
3. 새 요청 ID를 request header와 `prompthub.gateway.requestId` exchange attribute에 저장한다.
4. `prompthub.gateway.authenticated=false`를 기본값으로 저장하고 role attribute는 만들지 않는다.
5. 체인 호출 전에 응답 `beforeCommit` 콜백을 등록하고, 다운스트림 응답 헤더 병합이 끝난 commit 직전에 `X-Request-Id`를 Gateway UUID로 다시 `set`해 단일 값을 확정한다.
6. `System.nanoTime()` 시작값을 저장하고 Security와 Gateway 체인에 위임한다.

최외곽 필터에서 위조 사용자 헤더를 제거하므로 Security 401이나 미매칭 404를 포함해 모든 요청이 같은 신뢰 경계를 지난다.

### 6.3 인증과 다운스트림 헤더

`ForwardAuthFilter`는 Spring Security가 인증을 처리한 뒤 라우트가 매칭된 요청에만 실행된다.

1. 필터 진입 시 `Authorization`, `X-User-Id`, `X-User-Role`을 다시 제거한다. 사용자 헤더는 최외곽 필터에서도 제거하지만, `ForwardAuthFilter`를 단독으로 거치는 테스트와 향후 필터 순서 변경에도 신뢰 경계가 유지되도록 방어적으로 정리한다.
2. 익명 공개 요청은 사용자 헤더 없이 기존 chain으로 전달한다.
3. JWT가 있으면 기존과 동일하게 epoch를 검사하고 내부 authorize API를 호출한다.
4. authorize 결과가 `ACTIVE`이면 역할 정책 검사 전에 다음 attribute를 저장한다.
   - `prompthub.gateway.authenticated=true`
   - `prompthub.gateway.userRole=<BUYER|SELLER|ADMIN>`
5. 역할이 부족하면 기존과 동일하게 403을 반환한다. 이미 저장한 인증 성공 상태와 역할은 access 로그에 유지된다.
6. 역할이 충분하면 `X-User-Id`, `X-User-Role`을 `set`으로 주입해 다운스트림으로 전달한다.

`BLOCKED` 등 비활성 상태, authorize 거절, authorize 장애와 epoch 누락은 기본값인 `authenticated=false`를 유지한다. 사용자 ID는 exchange의 로그 attribute에 저장하지 않는다.

### 6.4 종료 관찰과 단일 기록

필터는 원래 오류를 변경하지 않고 관찰한다.

- `doOnError`에서 실제 예외 참조만 보관한다.
- `doFinally`에서 정상 완료, 오류와 `SignalType.CANCEL`을 하나의 종료 처리로 모은다.
- 한 subscription에는 `doFinally`가 한 번 실행되므로 access 이벤트도 한 번 생성한다.
- 로그 생성이나 출력 중 발생한 런타임 오류는 catch해 일반 `ERROR` 로그로 남기고 요청의 완료·오류·취소 결과를 바꾸지 않는다.

## 7. Access 로그 계약

고정 메시지는 `Gateway access`를 사용하고 다음 값을 SLF4J key-value로 추가한다. Spring Boot가 만드는 `level`, `@timestamp`, `message`, `logger_name` 등 기본 필드는 그대로 허용한다.

| 필드 | JSON 타입 | 값 결정 규칙 |
| --- | --- | --- |
| `eventType` | string | 항상 `GATEWAY_ACCESS` |
| `service` | string | 항상 `apigateway` |
| `requestId` | string | Gateway가 생성한 UUID |
| `method` | string | HTTP method |
| `path` | string | query string을 제외한 요청 path |
| `routeId` | string | `GATEWAY_ROUTE_ATTR`의 route ID, 없으면 `unknown` |
| `status` | number | 정상 응답 코드, 미설정 정상 완료 200, `ErrorResponse`의 내장 HTTP status, 그 밖의 예외 500, 취소 499 |
| `durationMs` | number | monotonic clock 기준 0 이상의 밀리초 |
| `authenticated` | boolean | exchange 인증 결과, 기본 `false` |
| `userRole` | string 또는 미포함 | 인증 성공 시 `BUYER`, `SELLER`, `ADMIN` 중 하나 |
| `clientIp` | string | XFF, remote address, `unknown` 순으로 결정 |
| `exceptionType` | string 또는 미포함 | 실제 예외가 있을 때 예외 클래스의 단순 이름 |
| `level` | string | Boot 로거가 생성하는 `INFO`, `WARN`, `ERROR` |

`userRole`과 `exceptionType`은 값이 없을 때 JSON key 자체를 추가하지 않는다. `ErrorResponse`에도 실제 예외 클래스의 단순 이름을 `exceptionType`으로 기록한다. `level`은 writer가 별도 key-value로 중복 추가하지 않고 선택한 SLF4J 로그 메서드의 최상위 필드를 사용한다.

### 7.1 상태와 레벨

종료 원인은 응답에 이미 설정된 상태보다 우선한다. 취소가 가장 먼저 적용되고, 예외가 Spring WebFlux의 `ErrorResponse`이면 예외가 제공하는 HTTP status와 해당 status의 레벨을 사용한다. 이 경우에도 `exceptionType`은 유지한다.

| 종료 결과 | 기록 status | 로그 레벨 |
| --- | ---: | --- |
| 정상 완료, 상태 미설정 | 200 | INFO |
| 정상 2xx·3xx | 실제 상태 | INFO |
| 정상 4xx | 실제 상태 | WARN |
| 정상 5xx | 실제 상태 | ERROR |
| `ErrorResponse` 예외 종료 | 예외의 내장 HTTP status | status 기준 INFO/WARN/ERROR |
| 그 밖의 예외 종료 | 500 | ERROR |
| 클라이언트 취소 | failure 존재 여부와 무관하게 499 | WARN |

### 7.2 처리 시간

필터 진입 시 `System.nanoTime()`을 저장하고 종료 시 차이를 밀리초로 변환한다. wall clock 변경의 영향을 받지 않으며 방어적으로 `0` 미만 값은 `0`으로 보정한다.

### 7.3 Route ID

Gateway가 라우트를 선택한 뒤에는 `ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR`에서 `Route#getId()`를 읽는다. Security가 먼저 거절했거나 라우트가 매칭되지 않은 경우 `unknown`을 기록한다.

## 8. Client IP 규칙

현재 NGINX는 `$proxy_add_x_forwarded_for`를 사용해 연결 주소를 XFF 목록의 마지막에 추가한다. 따라서 XFF 값을 오른쪽부터 검사해 마지막 유효 literal IPv4 또는 IPv6를 선택한다.

- 빈 값, `unknown`, hostname과 잘못된 IP 표현은 무시한다.
- IP 유효성 검사는 `InetAddress.getByName` 같은 DNS 조회 API를 사용하지 않고 엄격한 로컬 IPv4/IPv6 literal parser로 수행한다.
- IPv4와 IPv6의 숫자 자리는 ASCII `0`~`9`만 허용한다.
- XFF에 유효한 값이 없으면 `remoteAddress.getAddress().getHostAddress()`를 사용한다.
- remote address도 없으면 `unknown`을 사용한다.

이 신뢰 규칙은 외부 트래픽이 NGINX를 통과하고 NGINX가 마지막 값을 추가한다는 현재 배포 경계를 전제로 한다.

## 9. 인증 결과 행렬

| 요청 결과 | status | authenticated | userRole |
| --- | ---: | --- | --- |
| 익명 공개 요청 | 실제 응답 | false | 미포함 |
| JWT 검증 실패 | 401 | false | 미포함 |
| epoch 누락 또는 authorize 거절 | 401 | false | 미포함 |
| authorize 장애 | 503 | false | 미포함 |
| 비활성 계정 | 403 | false | 미포함 |
| ACTIVE지만 역할 부족 | 403 | true | 실제 역할 |
| ACTIVE이고 역할 충분 | 실제 응답 | true | 실제 역할 |

## 10. 보안과 개인정보

다음 값은 `GatewayAccessLog` 필드에 존재하지 않으며 writer에도 전달하지 않는다.

- `Authorization`
- Cookie
- userId와 `X-User-Id`
- query string
- 요청·응답 body
- 토큰, 결제 키와 기타 비밀값

Access writer는 실제 예외 객체를 access 이벤트에 전달하지 않고 `exceptionType`만 기록한다. 로깅 컴포넌트 자체의 장애를 알리는 일반 오류 로그에서만 로깅 실패 예외를 함께 기록한다.

## 11. 테스트 설계

테스트는 실패를 먼저 확인하고 최소 구현으로 통과시키는 TDD 순서로 작성한다.

### 11.1 `ClientIpResolverTest`

- 여러 XFF 값 중 오른쪽의 마지막 유효 IPv4 선택
- 잘못된 오른쪽 값은 건너뛰고 이전 유효 값 선택
- IPv6 literal 선택
- 유효 XFF가 없을 때 remote address 사용
- XFF와 remote address가 모두 없을 때 `unknown`

### 11.2 `GatewayAccessLogFactoryTest`

- 고정값, method와 query 없는 path
- route ID와 `unknown` fallback
- 응답 상태, 기본 200, `ErrorResponse`의 내장 status, 일반 예외 500, 취소 499
- 정상 status·`ErrorResponse`·일반 예외·failure가 함께 있는 취소의 로그 레벨 우선순위
- 0 이상 duration
- 인증 attribute와 선택적 role
- 예외가 있을 때만 `exceptionType`

### 11.3 `GatewayAccessLogWebFilterTest`

- 외부 요청 ID를 새 UUID로 교체
- request header, response header와 exchange attribute의 요청 ID 일치
- 다운스트림이 충돌하는 응답 요청 ID를 추가해도 commit된 응답에는 Gateway UUID 하나만 존재
- 외부 사용자 헤더 제거
- 정상, 오류와 취소에서 writer가 정확히 한 번 호출
- 원래 오류 전파와 취소 유지
- 로깅 내부 오류가 요청 결과를 변경하지 않음
- `/actuator/health`, 하위 경로, `/liveness`, `/readiness` 제외

### 11.4 `ForwardAuthFilterTest`

- 익명 공개 요청의 사용자 헤더와 Authorization 미전달
- 인증 성공 시 외부 값을 신뢰 값으로 덮어쓰기
- 인증 성공 attribute와 역할 저장
- 역할 부족 403에서도 `authenticated=true`와 역할 유지
- epoch 누락, authorize 거절·장애와 비활성 계정은 인증 기본값 유지

### 11.5 `GatewayAccessLogWriterTest`

Logback test appender로 실제 `ILoggingEvent`를 캡처해 다음을 검증한다.

- 모델 레벨에 맞는 SLF4J level
- 필수 key-value 전체
- 값이 없을 때 `userRole`, `exceptionType` 미포함
- 민감정보에 해당하는 key가 존재하지 않음

### 11.6 구조화 로그 통합 테스트

Spring Boot 실제 로깅 설정과 `OutputCaptureExtension`을 사용해 한 요청의 stdout을 캡처한다.

- `eventType=GATEWAY_ACCESS`인 줄이 정확히 한 개
- 응답의 `requestId`로 현재 요청의 access 줄만 격리
- Awaitility의 `during` 구간 동안 access 줄이 계속 정확히 한 개인지 확인
- 해당 한 줄을 JSON으로 파싱 가능
- 필수 access 필드가 JSON 최상위에 존재
- `status`, `durationMs`는 숫자이고 `authenticated`는 boolean
- 인증된 미매칭 요청의 `NoResourceFoundException`은 404 WARN과 `exceptionType`으로 기록
- query, Authorization, Cookie, userId와 body 식별 문자열이 access 줄에 없음

최종 회귀 명령은 다음과 같다.

```bash
./gradlew :apigateway:test
./gradlew :apigateway:build
```

## 12. 설정과 문서

`application.yml`에 다음 설정을 추가한다.

```yaml
logging:
  structured:
    format:
      console: logstash
```

`CorsConfig`는 브라우저가 응답의 요청 ID를 읽을 수 있도록 `X-Request-Id`를 exposed header로 등록한다.

`apigateway/CLAUDE.md`와 `docs/architecture/spring-cloud.md`에는 다음 내용을 반영한다.

- Gateway가 외부 요청 ID를 교체해 생성·전파한다.
- 외부 사용자 헤더와 Authorization을 신뢰하거나 전달하지 않는다.
- 내부 authorize 성공 시점과 역할 부족 403의 인증 상태
- access 이벤트 필드, 레벨과 제외 경로
- 일반 Gateway 로그를 포함한 stdout JSON 형식

## 13. 배포와 수용 기준

기존 CI/CD로 Gateway 이미지만 롤링 배포한다. DB, Kubernetes와 ELK 설정은 변경하지 않는다.

배포 후 정상 요청, 무토큰 401, 미매칭 404와 notification-service 503을 각각 호출해 다음을 확인한다.

1. 각 요청에 `GATEWAY_ACCESS` JSON 로그가 정확히 한 건 존재한다.
2. 응답 `X-Request-Id`와 Elasticsearch 문서의 `requestId`가 일치한다.
3. `status`, `durationMs`는 숫자이고 `authenticated`는 boolean이다.
4. `eventType=GATEWAY_ACCESS`로 검색할 수 있다.
5. 헬스 체크 access 로그는 없고 일반 Gateway 로그도 JSON으로 수집된다.

문제가 발생하면 Gateway 이미지를 이전 버전으로 롤백한다. 이번 변경에는 데이터나 인프라 스키마 마이그레이션이 없으므로 별도 데이터 복구 절차는 필요하지 않다.

## 14. 참고 자료

- [Spring Framework WebFlux WebFilter](https://docs.spring.io/spring-framework/reference/web/webflux/reactive-spring.html)
- [Spring Cloud Gateway Global Filters](https://docs.spring.io/spring-cloud-gateway/reference/4.2/spring-cloud-gateway/global-filters.html)
- [Spring Boot Structured Logging](https://docs.spring.io/spring-boot/reference/features/logging.html)
- [NGINX `$proxy_add_x_forwarded_for`](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)
