# Controller · 예외 처리 컨벤션

표현 계층(Controller)의 책임과 예외 처리 방식을 정의한다.

> 관련 문서: 계층 구조·DTO 변환 규칙은 `clean-architecture.md` 참고.

## 1. Controller 규칙

Controller 는 표현 계층의 진입점이며, HTTP 관심사만 책임진다.

- **Controller 는 HTTP 관심사만 처리한다.** 요청 수신 → 유스케이스 호출 → 응답 변환.
- **검증은 요청 경계에서 수행한다.** request DTO 에 `@Valid` / Bean Validation 으로 처리한다.
- **Controller 안에 비즈니스 규칙을 넣지 않는다.** 분기·계산·상태 판단은 도메인·유스케이스로.
- **Controller 가 Repository 에 직접 접근하지 않는다.** 반드시 유스케이스(포트)를 통한다.
- **조회(읽기) 흐름, 그리고 상태 변경이 단순한 명령 흐름은 유스케이스가 `~Response` 를 직접 반환할 수 있다.**
  이때 컨트롤러는 변환 없이 받아 내려준다(application 서비스가 `~Response` 를 만든다 —
  `clean-architecture.md` §1/§7 예외). 아래 예시는 `~Result` 를 거치는 일반 명령(상태 변경) 흐름이며,
  이 경우 컨트롤러가 `~Result` → `~Response` 변환을 한다.

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
- 단, **이 규칙은 `application`·`infrastructure`·`global` 계층에만 적용한다.** `domain` 계층은
  `ErrorCode`(=`HttpStatus`) 를 모르므로 `SettlementException` 을 던지지 않는다. (아래 2-4 참고)

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
  `BusinessException` 계열은 타입마다 핸들러를 두지 않는다. (`ErrorCode` 가 상태를 들고 있으므로 매핑이 필요 없다)
- 프레임워크 검증 예외(`MethodArgumentNotValidException` 등)와 미처리 `Exception`(fallback)만 별도로 잡는다.
- **도메인 순수 예외(2-4)는 핸들러에서 타입별로 잡아 `ErrorCode` 로 매핑한다.** 도메인 예외는
  `ErrorCode` 를 들지 않으므로(못 들으므로), "어떤 도메인 예외 → 어떤 `ErrorCode`" 변환을 핸들러가 맡는다.
  이때도 코드·메시지·상태의 출처는 `ErrorCode` enum 단일 관리(2-2)를 그대로 따른다.
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

### 2-4. 계층별 예외 — domain 은 순수 예외, 바깥 계층은 `SettlementException`

예외를 던지는 위치에 따라 타입을 나눈다. 기준은 **의존성 방향**(`clean-architecture.md` §1)이다.
`domain` 은 `global/exception`(=`ErrorCode`·`HttpStatus`) 을 import 할 수 없으므로 `SettlementException`
을 던질 수 없다. 그래서 domain 의 불변식 위반은 **순수 도메인 예외**로 던진다.

| 던지는 계층 | 사용하는 예외 | 위치 | 비고 |
| --- | --- | --- | --- |
| `domain` (model 불변식) | 순수 도메인 예외 (`extends RuntimeException`) | `domain/exception` | `ErrorCode`·`HttpStatus` 의존 금지 |
| `application` | `SettlementException(ErrorCode)` | `global/exception` 의 타입 사용 | 흐름 조율 중 비즈니스 위반 |
| `infrastructure` | `SettlementException(ErrorCode[, cause])` | 〃 | 외부 연동·잡 실행 실패 등 |
| `global/web` 등 | `SettlementException(ErrorCode)` | 〃 | 인증·권한 등 |

- **domain 순수 예외는 `ErrorCode` 를 들지 않는다.** 의미만 담고(필요하면 상태값 등 컨텍스트만),
  HTTP 상태로의 변환은 전적으로 핸들러가 맡는다. (2-3)
- **변환 매핑은 핸들러에 두되, 코드·메시지·상태의 단일 출처는 여전히 `ErrorCode` enum(2-2) 이다.**
  핸들러는 "이 도메인 예외 → 이 `ErrorCode`" 연결만 한다. 상태 코드를 핸들러에서 새로 만들지 않는다.
- 이 방식의 대가로 domain 예외 종류만큼 핸들러 매핑이 는다(2-3 의 "타입마다 핸들러 두지 않기"의 예외).
  domain 순수성을 우선해 감수한다.

```java
// domain/exception/SettlementBatchInvalidStateException.java — 순수 도메인 예외
public class SettlementBatchInvalidStateException extends RuntimeException {
    public SettlementBatchInvalidStateException(SettlementBatchStatus current) {
        super("정산 배치가 처리 중(PROCESSING) 상태가 아닙니다. current=" + current);
    }
}

// domain/model/SettlementBatch.java — 불변식은 도메인 메서드 안에서, 순수 예외로 보장
public void complete() {
    if (this.status != SettlementBatchStatus.PROCESSING) {
        throw new SettlementBatchInvalidStateException(this.status);
    }
    this.status = SettlementBatchStatus.COMPLETED;
}

// global/exception/GlobalExceptionHandler.java — 도메인 예외 → ErrorCode 매핑
@ExceptionHandler(SettlementBatchInvalidStateException.class)
public ResponseEntity<ErrorResponse> handle(SettlementBatchInvalidStateException e) {
    ErrorCode errorCode = SettlementErrorCode.SETTLEMENT_BATCH_INVALID_STATE;  // 출처는 enum
    return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
}
```
