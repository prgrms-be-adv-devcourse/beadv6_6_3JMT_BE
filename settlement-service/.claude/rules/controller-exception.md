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

- **도메인 전용 커스텀 예외를 사용한다.** (예: `SettlementAlreadyCompletedException`)
  일반 `RuntimeException`·`IllegalStateException` 남발 대신 의미 있는 예외 타입을 정의한다.
- **전역 예외 핸들러(`@RestControllerAdvice`)에서 일관되게 매핑한다.** 예외 → HTTP 상태·에러 코드 변환을 한곳에서 관리한다.
- **안정적인 API 에러 구조를 반환한다.** 모든 에러 응답은 동일한 형태(에러 코드·메시지 등)를 따른다.
- **persistence·프레임워크 내부 오류 상세를 그대로 클라이언트에 노출하지 않는다.**
  스택 트레이스·SQL·드라이버 메시지 등은 로그로만 남기고, 클라이언트에는 정제된 메시지를 준다.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SettlementAlreadyCompletedException.class)
    public ResponseEntity<ErrorResponse> handle(SettlementAlreadyCompletedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("SETTLEMENT_ALREADY_COMPLETED", e.getMessage()));
    }
}
```
