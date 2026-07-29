# UriComponentsBuilder.encode()가 쿼리 파라미터의 "+"를 인코딩하지 않아 OffsetDateTime 파싱이 깨짐

**날짜**: 2026-07-28
**상태**: Resolved
**분류**: Web / Spring Framework 7

## 환경

Spring Boot 4.1(Spring Framework 7.0.8), `payment-service` 통합 테스트(`RestTemplate` + `UriComponentsBuilder`).

## 증상

`GET /internal/audit-logs?since={OffsetDateTime}` 통합 테스트(`AuditLogQueryControllerIntegrationTest`)에서 `since` 파라미터를 포함한 요청만 매번 `400 Bad Request`로 실패했다. `since`를 생략한 요청은 정상 동작했다.

또한 애초에 `UriComponentsBuilder.fromHttpUrl(...)` 자체가 컴파일 에러였다:

```
error: cannot find symbol
        URI uri = UriComponentsBuilder.fromHttpUrl("http://localhost:" + port + "/internal/audit-logs")
        symbol:   method fromHttpUrl(String)
```

## 핵심

두 가지가 겹친 문제다.

1. **`fromHttpUrl`이 Spring Framework 7에서 제거됨.** 6.x까지 deprecated였던 메서드가 7.0에서 완전히 삭제되었다. 대체재는 `fromUriString`.
2. **`UriComponentsBuilder.encode()`가 쿼리 파라미터 값의 `+`를 인코딩하지 않는다.** RFC 3986 기준으로 `+`는 쿼리 컴포넌트의 sub-delim이라 문법적으로 유효한 문자이므로 Spring은 이를 그대로 둔다. 하지만 서블릿 컨테이너(Tomcat)는 쿼리 파라미터를 `application/x-www-form-urlencoded` 관례로 디코딩하기 때문에, 인코딩되지 않은 `+`를 공백으로 치환한다. `OffsetDateTime.toString()`이 만드는 `+09:00` 같은 오프셋이 ` 09:00`(공백)으로 깨져 파싱이 실패, `MethodArgumentTypeMismatchException` → 400으로 이어졌다.

즉, "URL을 문자열로 이어붙이지 말고 `UriComponentsBuilder`로 인코딩하라"는 일반적인 조언만으로는 이 문제를 피할 수 없다 — `encode()`를 붙여도 `+`는 그대로 남는다.

## 조사 과정

### 1. 컴파일 에러부터 해결

`fromHttpUrl` 미존재 → 실제 클래스패스의 `spring-web-7.0.8.jar`를 직접 열어 `UriComponentsBuilder`에 남아있는 정적 팩토리 메서드를 확인, `fromUriString`으로 교체.

### 2. 컴파일은 통과했지만 `since` 포함 요청만 400

`encode()`까지 붙였는데도 실패 — 처음엔 서버 측 `@RequestParam OffsetDateTime` 바인딩 문제로 의심했다.

### 3. 실제 인코딩 결과를 직접 확인

`jshell` 대신 임시 Java 파일(`/tmp/UriTest.java`)을 만들어 프로젝트가 실제로 참조하는 `spring-web-7.0.8.jar`/`spring-core-7.0.8.jar`를 클래스패스에 걸고 `UriComponentsBuilder.fromUriString(...).queryParam("since", since.toString()).build().encode().toUri()`의 실제 출력을 찍어봤다.

```
uri = http://localhost:8080/internal/audit-logs?since=2026-07-28T15:31:00.590361+09:00
```

`+`가 인코딩되지 않고 그대로 남아있는 것을 확인 — 여기가 근본 원인이었다.

### 4. 올바른 인코딩 방식 확인

`URLEncoder.encode(since.toString(), StandardCharsets.UTF_8)`로 값을 먼저 인코딩한 뒤, `UriComponentsBuilder.build(true)`(인자가 이미 인코딩되어 있다고 표시)로 조립하면 `+` → `%2B`로 정확히 치환됨을 같은 방식으로 재확인했다.

```
encodedSince = 2026-07-28T15%3A31%3A19.039869%2B09%3A00
uri = http://localhost:8080/internal/audit-logs?since=2026-07-28T15%3A31%3A19.039869%2B09%3A00
```

## 해결

`AuditLogQueryControllerIntegrationTest.java`에서 쿼리 파라미터 조립 방식을 변경했다.

```java
// Before (여전히 400)
URI uri = UriComponentsBuilder.fromUriString(url)
    .queryParam("since", since.toString())
    .build()
    .encode()
    .toUri();

// After
String encodedSince = URLEncoder.encode(since.toString(), StandardCharsets.UTF_8);
URI uri = UriComponentsBuilder.fromUriString(url)
    .queryParam("since", encodedSince)
    .build(true)
    .toUri();
```

## 검증

`AuditLogQueryControllerIntegrationTest`의 `since_파라미터로_그_이후_감사로그만_반환한다()`, `since_파라미터_생략_시_기본_15분_윈도우로_동작한다()` 둘 다 통과. `../gradlew :payment-service:test` 전체 스위트도 통과.

## 교훈 / 재발 방지

- Spring Framework 7로 올라오면서 6.x에서 deprecated였던 API(예: `fromHttpUrl`)가 실제로 제거된 경우가 있다 — 컴파일 에러가 나면 IDE 자동완성/기억에 의존하지 말고 실제 클래스패스의 jar를 확인한다.
- **쿼리 파라미터에 `OffsetDateTime`/`ZonedDateTime`처럼 `+`가 포함될 수 있는 값을 실어 보낼 때는 `UriComponentsBuilder(...).encode()`만으로 안전하다고 가정하지 않는다.** `+`는 RFC 3986 쿼리 문법상 유효해 Spring이 인코딩하지 않지만, 서블릿 컨테이너는 폼 인코딩 관례로 이를 공백으로 해석한다. `URLEncoder.encode(value, UTF_8)`로 값을 먼저 인코딩하고 `build(true)`로 조립해야 한다.
- 이런 종류의 "라이브러리가 예상과 다르게 동작하는지" 의심될 때는 실제 참조 중인 jar를 클래스패스에 걸고 최소 재현 코드로 직접 출력을 확인하는 것이, 테스트-수정 반복보다 빠르고 확실하다.
