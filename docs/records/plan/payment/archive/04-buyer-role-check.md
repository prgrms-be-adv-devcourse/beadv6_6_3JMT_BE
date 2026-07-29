# BUYER 역할 검증 구현 계획

`POST /api/v1/payments/confirm`, `POST /api/v1/payments/{paymentId}/refund` —
`X-User-Role` 헤더에 `BUYER` 역할이 없으면 403 PAY007 반환.

---

## 배경 및 설계 결정

### 역할 구조

Gateway가 `X-User-Role` 헤더에 쉼표 구분 문자열로 역할을 주입한다.

| 사용자 유형 | 헤더 값 예시 | BUYER 포함 |
|---|---|---|
| 구매자 | `BUYER` | ✅ |
| 판매자 | `BUYER,SELLER` | ✅ |
| 관리자(구매자 포함) | `BUYER,ADMIN` | ✅ |
| 관리자(전체 권한) | `BUYER,SELLER,ADMIN` | ✅ |
| 관리자(관리 전용) | `ADMIN` | ❌ → 403 |

`ADMIN`만 가진 계정이 결제/환불 API를 호출하는 케이스가 실재하므로 `BUYER` 유무 검증이 의미 있다.

### 설계 결정

- **검증 위치**: Controller 레이어 — `X-User-Id`와 동일한 레이어, 기존 Interceptor/Filter 인프라 없음, 엔드포인트 2개에 한정.
- **책임 소재**: payment-service가 담당 (팀 합의). Gateway는 역할 기반 접근 제어를 구현하지 않는다.
- **역할 미포함 시 에러**: PAY007, HTTP 403.

---

## 변경 파일

### 1. `application/exception/PaymentErrorCode.java` — 에러 코드 추가

기존 마지막 항목(`UNAUTHORIZED_REFUND`) 다음에 추가.

```java
INSUFFICIENT_ROLE(HttpStatus.FORBIDDEN, "PAY007", "결제/환불 권한이 없습니다.");
```

---

### 2. `presentation/PaymentController.java` — 헤더 수신 및 검증

두 메서드에 동일하게 적용.

#### `confirm()` 메서드

파라미터 추가:
```java
@Parameter(description = "사용자 역할 목록 (Gateway 주입, 쉼표 구분)", required = true,
    example = "BUYER,SELLER")
@RequestHeader("X-User-Role") String userRoles,
```

메서드 진입 직후 추가:
```java
if (Arrays.stream(userRoles.split(",")).noneMatch("BUYER"::equals)) {
    throw new BusinessException(PaymentErrorCode.INSUFFICIENT_ROLE);
}
```

#### `refund()` 메서드

동일하게 `@RequestHeader("X-User-Role") String userRoles` 추가 및 같은 BUYER 확인 로직 추가.

#### import 추가

```java
import java.util.Arrays;
import com.prompthub.exception.BusinessException;
```

---

### 3. `presentation/PaymentControllerTest.java` — 테스트 수정

#### 기존 테스트 — `X-User-Role: BUYER` 헤더 추가

현재 모든 테스트가 `X-User-Id`만 설정하고 있어 `X-User-Role` 추가 후 400으로 깨진다.
`header("X-User-Role", "BUYER")` 한 줄씩 추가.

```java
// 변경 전
mockMvc.perform(post("/api/v1/payments/confirm")
    .header("X-User-Id", UUID.randomUUID().toString())
    ...

// 변경 후
mockMvc.perform(post("/api/v1/payments/confirm")
    .header("X-User-Id", UUID.randomUUID().toString())
    .header("X-User-Role", "BUYER")
    ...
```

#### 신규 테스트 추가

```java
@Test
void BUYER_역할_없으면_결제승인_403_PAY007() throws Exception {
    mockMvc.perform(post("/api/v1/payments/confirm")
            .header("X-User-Id", UUID.randomUUID().toString())
            .header("X-User-Role", "ADMIN")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                new ConfirmPaymentRequest("toss-key", UUID.randomUUID(), 10_000)
            )))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PAY007"));
}

@Test
void BUYER_역할_없으면_환불_403_PAY007() throws Exception {
    mockMvc.perform(post("/api/v1/payments/{paymentId}/refund", UUID.randomUUID())
            .header("X-User-Id", UUID.randomUUID().toString())
            .header("X-User-Role", "ADMIN"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PAY007"));
}
```

---

## Swagger 문서 변경

두 메서드의 `@ApiResponses`에 403 응답 추가.

```java
@ApiResponse(responseCode = "403", description = "BUYER 역할 없음(PAY007)",
    content = @Content(mediaType = "application/json",
        schema = @Schema(implementation = ErrorResponse.class),
        examples = @ExampleObject(value = """
            {
              "success": false,
              "data": null,
              "message": "결제/환불 권한이 없습니다.",
              "code": "PAY007"
            }
            """)))
```

---

### 4. `.claude/docs/api-design.md` — API 설계 문서 수정

#### 공통 헤더 표 — `X-User-Role` 타입 및 설명 수정

```diff
- | `X-User-Role` | Enum   | 인증 시 ✅ | `BUYER` / `SELLER` / `ADMIN` |
+ | `X-User-Role` | String | 인증 시 ✅ | 쉼표 구분 복합 역할 (예: `BUYER,SELLER`) |
```

#### `POST /confirm` Responses 표 — A004 → PAY007 교체

Gateway가 역할 기반 차단을 구현하지 않으므로 A004는 실제로 반환되지 않는다. BUYER 검증은 payment-service Controller가 담당하므로 PAY007로 교체.

```diff
- | `403` | 권한 없음                | `A004`  |
+ | `403` | BUYER 역할 없음           | `PAY007` |
```

#### `POST /{paymentId}/refund` — 제약 조건 및 Responses 표 수정

제약 조건의 "BUYER, SELLER만 가능 (관리자 불가)" 표현은 부정확하다. 관리자도 BUYER 역할을 함께 보유하면 접근 가능하므로 BUYER 역할 유무가 기준임을 명시.

```diff
- 요청 주체: `BUYER`, `SELLER`만 가능 (관리자 불가)
+ 요청 주체: `BUYER` 역할 보유자만 가능 (BUYER 없는 관리 전용 계정 불가)
```

Responses 표:

```diff
- | `403` | 권한 없음 (역할)          | `A004`  |
+ | `403` | BUYER 역할 없음           | `PAY007` |
```

---

### 5. `.claude/rules/api-error-handling.md` — 역할 검증 패턴 추가

역할 기반 인가를 Controller 진입부에서 직접 수행한다는 패턴이 문서화되어 있지 않다. 추가.

```diff
+ - **역할 기반 인가는 Controller 메서드 진입부**에서 `@RequestHeader`로 수신 후 직접 검증한다. Interceptor/Filter는 현재 사용하지 않는다 (엔드포인트 한정 규칙이므로 Controller가 적절).
```

---

## 구현 순서

1. `PaymentErrorCode` — `INSUFFICIENT_ROLE` 추가
2. `PaymentController` — 두 메서드에 헤더 수신 + BUYER 확인 추가, Swagger 403 문서 추가
3. `PaymentControllerTest` — 기존 테스트 헤더 수정 + 신규 403 테스트 추가
4. `.claude/docs/api-design.md` — 헤더 표, Confirm/Refund Responses 표, Refund 제약 조건 수정
5. `.claude/rules/api-error-handling.md` — 역할 검증 패턴 항목 추가
6. `./gradlew test --tests "*.PaymentControllerTest"` 실행 확인

---

## 변경 파일 요약

```
수정 (5개)
├── application/exception/PaymentErrorCode.java       — PAY007 추가
├── presentation/PaymentController.java               — X-User-Role 수신 + BUYER 검증 + Swagger 403
├── presentation/PaymentControllerTest.java           — 기존 테스트 헤더 수정 + 신규 403 테스트 2개
├── .claude/docs/api-design.md                        — X-User-Role 형식, 403 에러코드 A004→PAY007, Refund 제약 조건
└── .claude/rules/api-error-handling.md               — 역할 기반 인가 패턴 추가
```
