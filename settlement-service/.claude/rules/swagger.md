# Swagger(OpenAPI) 컨벤션

REST API 문서화를 위한 Swagger(springdoc-openapi) 애너테이션 사용 규칙을 정의한다.
`io.swagger.v3.oas.annotations` 기반으로 작성한다.

> 관련 문서
> - Controller 책임·예외 처리: `controller-exception.md`
> - 계층·DTO 변환 규칙: `clean-architecture.md`

## 1. 적용 범위

문서화 애너테이션은 표현 계층(`presentation`)에만 둔다.

- 컨트롤러: `@Tag`, `@Operation`, `@ApiResponses`/`@ApiResponse`, `@Parameter`
- 요청·응답 DTO: `@Schema`
- application·domain·infrastructure 계층 **코드에는 Swagger 애너테이션을 직접 작성하지 않는다.**

> **조회 응답 예외:** `clean-architecture.md` §1/§7에 따라 조회(읽기) 유스케이스는 application 서비스가
> presentation `~Response`를 직접 만들어 반환할 수 있다. 이때 application 이 `@Schema` 가 붙은
> `~Response`를 **반환·참조**하는 것은 위반이 아니다. 금지되는 것은 **application 코드에 Swagger
> 애너테이션(`@Schema`·`@Operation` 등)을 직접 다는 것**이며, `@Schema` 정의 자체는 그대로 presentation
> `~Response`에만 둔다. (Swagger 애너테이션은 표현 계층 클래스에만 존재한다)

도메인 모델(`@Entity`)에는 `@Schema`를 붙이지 않는다. 문서화는 항상 `~Request` / `~Response` DTO 에서 한다.
(도메인 모델을 응답으로 직접 노출하지 않는 규칙은 `clean-architecture.md` 참고)

## 2. 컨트롤러 — `@Tag` / `@Operation`

- 컨트롤러 클래스에는 `@Tag` 를 붙여 API 그룹을 묶는다.
- 각 핸들러 메서드에는 `@Operation` 으로 요약·설명을 단다.
- 요청 경로 prefix 는 하드코딩하지 않고 `${api.init}` 프로퍼티로 주입한다.

| 애너테이션 | 위치 | 속성 | 작성 언어 |
| --- | --- | --- | --- |
| `@Tag` | 컨트롤러 클래스 | `name`, `description` | `name`: 영문, `description`: 한글 |
| `@Operation` | 핸들러 메서드 | `summary`, `description` | 한글 |

- `@Tag(name)` 은 Swagger UI 의 그룹 제목이 되므로 기능 단위로 짧고 일관되게 짓는다. (예: `Settlement Batch`)
- `summary` 는 한 줄 요약, `description` 은 동작·대상을 풀어 설명한다.

```java
@RestController
@RequestMapping("${api.init}/settlements/batch")
@RequiredArgsConstructor
@Tag(name = "Settlement Batch", description = "정산 배치잡 API")
public class SettlementBatchController {

    private final SettlementUseCase settlementUseCase;

    @PostMapping
    @Operation(summary = "정산 배치잡 실행",
            description = "정산 기준일의 미정산 PAID 주문을 정산하는 Batch Job을 실행합니다.")
    public ResponseEntity<SettlementJobResponse> run(
            @Valid @RequestBody RunSettlementJobRequest request) {
        ...
    }
}
```

## 3. DTO — `@Schema`

요청·응답 DTO 는 `record` 로 작성하고 `@Schema` 로 문서화한다.

- **타입(record) 선언부**에 `@Schema(description = ...)` 로 DTO 전체 설명을 단다.
- **각 필드(record 컴포넌트)**에 `@Schema(description = ...)` 를 단다.
- 값의 형태가 모호한 필드(날짜·코드·UUID 등)는 `example` 을 함께 제공한다.

| 위치 | 필수 속성 | 선택 속성 |
| --- | --- | --- |
| record 선언부 | `description` | — |
| record 필드 | `description` | `example` (형태가 모호한 값에 권장) |

```java
@Schema(description = "정산 배치잡 실행 요청")
public record RunSettlementJobRequest(
        @Schema(description = "정산 기준일", example = "2026-06-03")
        LocalDate settlementDate,

        @Schema(description = "요청 수행자 ID(UUID)")
        UUID actorId
) {
}
```

```java
@Schema(description = "정산 배치잡 실행 응답")
public record SettlementJobResponse(
        @Schema(description = "Job Execution ID")
        Long jobExecutionId,

        @Schema(description = "Job 이름")
        String jobName,

        @Schema(description = "실행 상태")
        String status,

        @Schema(description = "시작 시각")
        LocalDateTime startTime
) {

    public static SettlementJobResponse from(JobExecution jobExecution) { ... }
}
```

## 4. 응답 명세 — `@ApiResponses` / `@ApiResponse`

각 핸들러 메서드에는 `@ApiResponses` 로 **성공 응답과 발생 가능한 비즈니스 예외 응답을 모두 명시한다.**
응답 코드만 보고 API 의 성공·실패 케이스를 파악할 수 있어야 한다.

### 4-1. 성공(2xx) 응답

- 메서드가 실제 반환하는 HTTP 상태에 맞춰 성공 코드를 적는다.
  생성은 `201`, 수정·조회는 `200`, 본문 없는 삭제는 `204`.
- 응답 본문이 있으면 `content = @Content(schema = @Schema(implementation = ...))` 로 스키마를 연결한다.
- **`implementation` 에는 도메인 모델(`@Entity`)이 아니라 `~Response` DTO 를 지정한다.**
  (도메인 모델을 응답으로 노출하지 않는 규칙 — `clean-architecture.md` 참고)
- 본문이 없으면(`204` 등) `content` 를 생략한다.

| 동작 | 성공 코드 | 본문 |
| --- | --- | --- |
| 생성(POST) | `201` | `~Response` |
| 조회·수정(GET/PUT) | `200` | `~Response` |
| 삭제(DELETE) | `204` | 없음 |

### 4-2. 비즈니스 예외(4xx/5xx) 응답

- **해당 엔드포인트에서 터질 수 있는 도메인 예외를 응답으로 명시한다.**
  예: 상태 충돌 → `409`, 리소스 없음 → `404`, 요청 값 오류 → `400`.
- 응답 코드·설명은 전역 예외 핸들러(`@RestControllerAdvice`)의 매핑과 일치시킨다.
  핸들러가 `SettlementAlreadyCompletedException → 409` 로 매핑하면 문서에도 `409` 를 적는다.
- 에러 본문 스키마는 공통 에러 응답(`ErrorResponse`)으로 연결한다.
- 예외별 의미는 `description` 에 한글로 적는다. (예: `"이미 정산 완료된 건"`)

> 어떤 예외가 어떤 상태로 매핑되는지는 `controller-exception.md` 의 전역 예외 핸들러 규칙을 따른다.
> Swagger 문서는 그 매핑을 **그대로 반영만** 하고, 새로운 상태 코드를 임의로 만들지 않는다.

### 4-3. 경로·쿼리 파라미터 — `@Parameter`

`@PathVariable` / `@RequestParam` 에는 `@Parameter(description = ...)` 로 설명을 단다.

```java
@PostMapping
@Operation(summary = "정산 생성", description = "신규 정산 건을 생성합니다.")
@ApiResponses({
        @ApiResponse(responseCode = "201", description = "생성 성공",
                content = @Content(schema = @Schema(implementation = SettlementResponse.class))),
        @ApiResponse(responseCode = "400", description = "요청 값 오류",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public ResponseEntity<SettlementResponse> create(
        @Valid @RequestBody CreateSettlementRequest request) {
    SettlementResult result = settlementUseCase.create(request.toCommand());
    return ResponseEntity.status(HttpStatus.CREATED).body(SettlementResponse.from(result));
}

@PostMapping("/{settlementId}/complete")
@Operation(summary = "정산 완료 처리", description = "정산 건을 완료 상태로 전환합니다.")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "완료 성공",
                content = @Content(schema = @Schema(implementation = SettlementResponse.class))),
        @ApiResponse(responseCode = "404", description = "정산 건 없음",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "이미 정산 완료된 건",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public SettlementResponse complete(
        @Parameter(description = "정산 UUID") @PathVariable UUID settlementId) {
    return SettlementResponse.from(settlementUseCase.complete(settlementId));
}

@DeleteMapping("/{settlementId}")
@Operation(summary = "정산 삭제", description = "정산 건을 삭제합니다.")
@ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 성공"),
        @ApiResponse(responseCode = "404", description = "정산 건 없음",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public ResponseEntity<Void> delete(
        @Parameter(description = "정산 UUID") @PathVariable UUID settlementId) {
    settlementUseCase.delete(settlementId);
    return ResponseEntity.noContent().build();
}
```

## 5. 작성 원칙

- **모든 공개 API 엔드포인트에 `@Operation` 을 단다.** 요약 없는 엔드포인트를 만들지 않는다.
- **모든 엔드포인트에 성공 응답과 발생 가능한 비즈니스 예외 응답을 `@ApiResponses` 로 명시한다.**
- **성공 응답 본문 스키마는 도메인 모델이 아니라 `~Response` DTO 를 가리킨다.**
- **에러 응답 코드는 전역 예외 핸들러 매핑과 일치시킨다.** 문서와 실제 응답이 어긋나지 않게 한다.
- **모든 요청·응답 DTO 와 그 필드에 `@Schema` 를 단다.** 설명 없는 필드를 남기지 않는다.
- **example 은 실제 유효한 값으로 적는다.** 날짜는 `ISO-8601`(`2026-06-03`) 형식을 따른다.
- **문서화 애너테이션이 비즈니스 로직에 영향을 주지 않게 한다.** 검증은 `@Schema` 가 아니라
  Bean Validation(`@Valid` / `@NotNull` 등)으로 한다. (`controller-exception.md` 참고)
- 응답 DTO 변환은 정적 팩토리(`from`, `of`)에 두는 기존 규칙을 그대로 따른다.
