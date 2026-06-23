# Controller · 예외 처리 컨벤션

표현 계층(Controller)의 책임과 예외 처리 방식을 정의한다.

> 관련 문서: 계층 구조·DTO 변환 규칙은 `clean-architecture.md` 참고.

## 1. Controller 규칙

Controller 는 표현 계층의 진입점이며, HTTP 관심사만 책임진다.

- **Controller 는 HTTP 관심사만 처리한다.** 요청 수신 → 유스케이스 호출 → 응답 변환.
- **검증은 요청 경계에서 수행한다.** request DTO 에 `@Valid` / Bean Validation 으로 처리한다.
- **Controller 안에 비즈니스 규칙을 넣지 않는다.** 분기·계산·상태 판단은 도메인·유스케이스로.
- **Controller 가 Repository 에 직접 접근하지 않는다.** 반드시 유스케이스(포트)를 통한다.

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementUseCase settlementUseCase;   // Repository 직접 주입 금지

    @PostMapping
    public SettlementResponse create(@Valid @RequestBody CreateSettlementRequest request) {
        SettlementResult result = settlementUseCase.create(request.toCommand());
        return SettlementResponse.from(result);
    }
}
```

## 2. 예외 처리

예외 인프라는 common-module 을 베이스로 쓰고, 서비스별로 커스텀한다. 위치는 `global/exception`.
(패키지 규칙은 `clean-architecture.md` 참고)

### 2-1. 비즈니스 예외 = common `BusinessException` 커스텀

- **common-module 의 `BusinessException(ErrorCode)` 를 베이스로, 서비스 전용 예외로 확장한다.**
  (예: `SettlementException extends BusinessException`) 일반 `RuntimeException`·`IllegalStateException`
  남발 대신 이 타입으로 던진다.
- 예외를 던질 때 **의미는 `ErrorCode` 로 전달한다.** 외부 원인을 감싸야 하면 cause 를 받는 생성자를 둔다.

```java
public class SettlementException extends BusinessException {
    public SettlementException(ErrorCode errorCode) { super(errorCode); }
    public SettlementException(ErrorCode errorCode, String message) { super(errorCode, message); }
    public SettlementException(ErrorCode errorCode, Throwable cause) { super(errorCode); initCause(cause); }
}

throw new SettlementException(SettlementErrorCode.SETTLEMENT_BATCH_NOT_FOUND);
throw new SettlementException(SettlementErrorCode.SETTLEMENT_JOB_EXECUTION_FAILED, e);
```

### 2-2. 에러 코드는 `ErrorCode` enum 단일 관리

- **코드·메시지·HTTP 상태는 `ErrorCode` 구현 enum 한곳에서 관리한다.** (예: `SettlementErrorCode`)
  핸들러에 상태 매핑을 흩지 않는다. 새 에러는 enum 에 항목만 추가한다.
- enum 은 common `ErrorCode`(`getCode`·`getMessage`·`getStatus`) 를 구현한다. `HttpStatus` 를 들고
  있으므로 **domain 에 두지 않고 `global/exception` 에 둔다.**

```java
@Getter
@RequiredArgsConstructor
public enum SettlementErrorCode implements ErrorCode {

    SETTLEMENT_BATCH_NOT_FOUND("S-001", "정산 배치를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    SETTLEMENT_JOB_EXECUTION_FAILED("S-002", "정산 배치 잡 실행에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus status;
}
```

### 2-3. 전역 핸들러는 `BusinessException` 을 제네릭하게 한 번만 잡는다

- **`@RestControllerAdvice` 에서 `BusinessException` 을 한 번 잡아 `errorCode` 로 응답을 만든다.**
  예외 타입마다 핸들러를 두지 않는다. (`ErrorCode` 가 상태를 들고 있으므로 매핑이 필요 없다)
- 프레임워크 검증 예외(`MethodArgumentNotValidException` 등)와 미처리 `Exception`(fallback)만 별도로 잡는다.
- **안정적인 API 에러 구조를 반환한다.** 응답 본문은 common `ErrorResponse` 로 통일한다.
- **persistence·프레임워크 내부 오류 상세를 그대로 클라이언트에 노출하지 않는다.**
  스택 트레이스·SQL·드라이버 메시지 등은 로그로만 남기고, 클라이언트에는 정제된 메시지를 준다.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handle(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, e.getMessage()));
    }
}
```
