# 어드민 정산 상태 변경(Settlement Status Change) 설계

작성일: 2026-06-24
이슈: #64

## 배경 / 문제

정산 예정 목록에서 관리자가 각 정산 건의 상태를 직접 바꾼다(승인·보류·지급 등).
현재 정산 기능은 조회(요약·목록)만 구현돼 있어 상태를 전이시킬 API가 없다.

UI 액션 버튼(승인 / 보류 / 보류 취소 / 지급 / 지급 보류 / 보류 해제)에 대응하는
상태 변경 PATCH API 6종을 추가한다. **취소(cancel)는 별도 이슈/세션**에서 다룬다.
([settlement-cancel-design](2026-06-24-settlement-cancel-design.md))

## 결정 사항

| 항목 | 결정 |
| --- | --- |
| 범위 | 6종(approve / hold / release-hold / payout / payout-hold / payout-hold/release). cancel 제외 |
| 요청 본문 | **없음**. `settlementId`(path) + `X-User-Id` 헤더(actorId)만 |
| failureReason | **건드리지 않음**. 배치 실패 사유 칸이며 어드민 액션 사유가 아님 |
| actorId | `X-User-Id` 헤더로 **받아만 둠**(로깅용). 엔티티에 저장하지 않음 |
| payoutReference | 외부 송금 연동 전이라 **이번 범위에서 생성하지 않음**(null 유지) |
| 응답 DTO | **통합 `SettlementStatusResponse` 1개**를 6종이 공유. null 필드는 미출력 |
| 비즈니스 시각 | `confirmedAt`/`paidAt`은 **서비스에서 주입**(domain-model.md §6, cancel 설계와 정합) |
| 상태 전이 위반 | 도메인 순수 예외 `SettlementInvalidStateException` 하나로 통일(409) |

## 상태 전이 규칙

| API | 선행 조건(검증) | settlementStatus | payoutStatus | 기록 |
| --- | --- | --- | --- | --- |
| approve | settlementStatus = PENDING_APPROVAL | → APPROVED | NOT_READY → READY | confirmedAt |
| hold | PENDING_APPROVAL | → SETTLEMENT_ON_HOLD | 유지 | — |
| release-hold | SETTLEMENT_ON_HOLD | → PENDING_APPROVAL | 유지 | — |
| payout | APPROVED & payout=READY | 유지 | READY → PAID | paidAt |
| payout-hold | APPROVED & payout=READY | 유지 | READY → PAYOUT_ON_HOLD | — |
| payout-hold/release | APPROVED & payout=PAYOUT_ON_HOLD | 유지 | PAYOUT_ON_HOLD → READY | — |

## 설계

### 1. 도메인 계층

#### 1-1. `Settlement`에 상태 전이 메서드 6종 신설

상태 전이를 도메인 메서드로 표현하고, 불변식(선행 상태)을 메서드 안에서 검증한다.
선행 조건 위반 시 순수 도메인 예외 `SettlementInvalidStateException`을 던진다.

```java
public void approve(LocalDateTime confirmedAt)   // PENDING_APPROVAL → APPROVED, payout READY, confirmedAt
public void hold()                               // PENDING_APPROVAL → SETTLEMENT_ON_HOLD
public void releaseHold()                        // SETTLEMENT_ON_HOLD → PENDING_APPROVAL
public void payout(LocalDateTime paidAt)         // payout READY → PAID, paidAt
public void payoutHold()                         // payout READY → PAYOUT_ON_HOLD
public void releasePayoutHold()                  // payout PAYOUT_ON_HOLD → READY
```

- 각 메서드는 진입 시 현재 상태가 선행 조건과 맞는지 검증하고, 어긋나면
  `SettlementInvalidStateException`(현재 상태·시도한 전이 컨텍스트 포함)을 던진다.
- `payout` / `payoutHold` / `releasePayoutHold`는 `settlementStatus == APPROVED`도 함께 검증한다.
- `confirmedAt`·`paidAt` 시각은 외부(애플리케이션 서비스)에서 주입한다. (domain-model.md §6,
  cancel 설계의 `canceledAt` 주입과 동일한 결)
- `payoutReference`는 변경하지 않는다(이번 범위 비대상).
- `failureReason`은 어떤 메서드에서도 건드리지 않는다.

#### 1-2. 순수 도메인 예외 (`domain/exception`)

`ErrorCode`·`HttpStatus`에 의존하지 않는 순수 `RuntimeException`. (controller-exception.md §2-4,
기존 `SettlementBatchInvalidStateException` 패턴과 동일)

- `SettlementInvalidStateException` — 현재 정산/지급 상태에서 시도한 전이가 불가능할 때.
  메시지에 현재 `settlementStatus`·`payoutStatus`와 시도한 액션을 담는다.

> 6종은 일반 상태 전이라 단일 예외로 묶는다. cancel 설계가 정의한
> `SettlementAlreadyPaidException`·`SettlementAlreadyCancelledException`은 취소 고유의 의미라
> 그대로 분리해 둔다. 두 트랙은 독립적이다.

### 2. 애플리케이션 계층

#### 2-1. `SettlementUseCase`에 명령 메서드 6종 추가

같은 `Settlement` 애그리거트 연산이므로 **별도 UseCase로 분리하지 않고** 기존 포트에 모은다.
(clean-architecture.md §4-1 — 같은 엔티티 연산은 한 `~UseCase`에. cancel도 동일 포트에 합류)

```java
SettlementStatusResult approve(UUID settlementId);
SettlementStatusResult hold(UUID settlementId);
SettlementStatusResult releaseHold(UUID settlementId);
SettlementStatusResult payout(UUID settlementId);
SettlementStatusResult payoutHold(UUID settlementId);
SettlementStatusResult releasePayoutHold(UUID settlementId);
```

#### 2-2. `SettlementApplicationService` 구현 (`@Transactional`)

클래스 레벨 `@Transactional(readOnly = true)`는 유지하고, 명령 메서드 6종에만 `@Transactional`을
메서드 단위로 얹는다(쓰기 트랜잭션). 각 메서드 공통 흐름:

1. `settlementRepository.findById(settlementId)` → 없으면 `SettlementException(SETTLEMENT_NOT_FOUND)`
2. 도메인 메서드 호출(`settlement.approve(now)` 등) — 불변식 위반 시 순수 도메인 예외 전파
3. `settlementRepository.save(settlement)`
4. `SettlementStatusResult.from(settlement)` 반환

> `now`(confirmedAt/paidAt)는 서비스에서 생성해 도메인에 주입한다(테스트 용이성).
> actorId는 헤더로 받아 서비스까지 전달하되, 이번 범위에서는 로깅 용도로만 쓰고 영속화하지 않는다.

#### 2-3. 아웃바운드 포트 보강

`SettlementRepository`에 단건 조회 `findById(UUID)`가 없으면 추가한다(어댑터는 기존
`SettlementJpaRepository.findById`에 위임). cancel 설계도 동일 메서드를 필요로 하므로, 먼저
머지되는 쪽이 추가하고 다른 쪽은 재사용한다.

#### 2-4. `SettlementStatusResult` (application/dto)

명령(상태 변경) 흐름이므로 컨벤션 §7에 따라 서비스는 `~Result`까지만 만들고, `~Response` 변환은
컨트롤러가 맡는다(조회 예외와 구분 — 서비스가 `~Response`를 직접 만들지 않음). 도메인 모델이
표현 계층으로 새지 않도록 `Settlement` → `SettlementStatusResult`로 끊는다.

- 필드: `settlementId, settlementStatus, payoutStatus, displayStatus, confirmedAt, paidAt,
  payoutReference, failureReason, updatedAt`
- `from(Settlement)` 정적 팩토리. `displayStatus`는 `settlement.displayStatus()`로 채운다.

### 3. 표현 계층

`SettlementController`에 PATCH 엔드포인트 6종 추가(같은 애그리거트라 기존 컨트롤러가 받는다).
경로 prefix는 `${api.init}`로 주입한다(swagger.md §2).

```
PATCH {api.init}/admin/settlements/{settlementId}/approve
PATCH {api.init}/admin/settlements/{settlementId}/hold
PATCH {api.init}/admin/settlements/{settlementId}/release-hold
PATCH {api.init}/admin/settlements/{settlementId}/payout
PATCH {api.init}/admin/settlements/{settlementId}/payout-hold
PATCH {api.init}/admin/settlements/{settlementId}/payout-hold/release
```

- `@RequestHeader("X-User-Id") UUID actorId`, `@PathVariable UUID settlementId`
- 반환: `ApiResult<SettlementStatusResponse>` (기존 조회 엔드포인트와 동일한 공통 응답 래퍼)
- 컨트롤러는 `SettlementStatusResponse.from(result)`로 변환만 한다.
- Swagger: `@Operation`, `@Parameter`(settlementId), `@ApiResponses`:
  - 200 — 성공 (`SettlementStatusResponse`)
  - 404 — 정산 없음 (`ErrorResponse`)
  - 409 — 전이 불가 상태 (`ErrorResponse`)

#### 3-1. `SettlementStatusResponse` (presentation/dto/response)

```java
@Schema(description = "정산 상태 변경 응답")
public record SettlementStatusResponse(
    UUID settlementId,
    SettlementStatus settlementStatus,
    PayoutStatus payoutStatus,
    SettlementDisplayStatus displayStatus,
    LocalDateTime confirmedAt,
    LocalDateTime paidAt,
    String payoutReference,
    String failureReason,
    LocalDateTime updatedAt
) {
    public static SettlementStatusResponse from(SettlementStatusResult result) { ... }
}
```

- 각 필드에 `@Schema(description = ...)`, 형태가 모호한 값(UUID·날짜)에 `example`.
- null 필드 미출력(`@JsonInclude(NON_NULL)`) — approve가 아닌데 confirmedAt이 내려가는 일이 없도록.
- 6종이 공유하는 **표준 응답**으로 정한다. cancel도 구현 세션에서 이 DTO를 따르도록 권장한다
  (cancel 설계의 응답 형태는 초안이며 코드 현황에 맞춘다고 열어 둠).

### 4. 예외 매핑 (`global/exception`)

#### 4-1. `SettlementErrorCode` 항목 추가

| 코드 | 메시지 | 상태 |
| --- | --- | --- |
| `SETTLEMENT_NOT_FOUND` | 정산을 찾을 수 없습니다. | 404 NOT_FOUND |
| `SETTLEMENT_INVALID_STATE` | 현재 상태에서 변경할 수 없는 정산입니다. | 409 CONFLICT |

> `SETTLEMENT_NOT_FOUND`는 cancel 설계도 추가 예정이다. 먼저 머지되는 쪽이 추가하고 다른 쪽은
> 재사용한다(중복 정의 금지).

#### 4-2. `GlobalExceptionHandler` 매핑 추가

도메인 순수 예외를 타입별로 잡아 `ErrorCode`로 매핑한다. (controller-exception.md §2-3/§2-4)

- `SettlementInvalidStateException` → `SETTLEMENT_INVALID_STATE`(409)
- `SETTLEMENT_NOT_FOUND`는 애플리케이션이 `SettlementException`으로 던지므로 기존
  `BusinessException` 핸들러가 처리한다(추가 매핑 불필요).

## 테스트 우선 (test-first-unit-test)

구현 전에 단위 테스트를 먼저 작성한다.

**도메인 — `Settlement` 상태 전이 6종**
- approve: PENDING_APPROVAL → APPROVED + payout READY + confirmedAt 세팅 / 그 외 상태에서 호출 → 예외
- hold: PENDING_APPROVAL → SETTLEMENT_ON_HOLD / 그 외 → 예외
- releaseHold: SETTLEMENT_ON_HOLD → PENDING_APPROVAL / 그 외 → 예외
- payout: APPROVED & READY → PAID + paidAt / settlementStatus≠APPROVED 또는 payout≠READY → 예외
- payoutHold: APPROVED & READY → PAYOUT_ON_HOLD / 위반 → 예외
- releasePayoutHold: APPROVED & PAYOUT_ON_HOLD → READY / 위반 → 예외
- 각 전이 후 `displayStatus()`가 기대 표시 상태를 반환

**애플리케이션 — 6종 각각**
- 정상: 해당 상태로 전이된 결과 반환, save 호출
- 없는 정산 → `SettlementException(SETTLEMENT_NOT_FOUND)`
- 잘못된 상태 → 도메인 예외 전파(서비스가 삼키지 않음)

## 영향 범위 요약

| 계층 | 변경 |
| --- | --- |
| domain/model | `Settlement`에 상태 전이 메서드 6종 |
| domain/exception | `SettlementInvalidStateException` 신설 |
| domain/repository | `SettlementRepository.findById(UUID)`(없으면 추가) |
| application/usecase | `SettlementUseCase`에 명령 메서드 6종 |
| application/service | `SettlementApplicationService`에 6종 구현 |
| application/dto | `SettlementStatusResult` 신설 |
| infrastructure/persistence | 어댑터에 `findById` 위임(없으면) |
| presentation/controller | PATCH 6종 엔드포인트 |
| presentation/dto/response | `SettlementStatusResponse` 신설 |
| global/exception | `SettlementErrorCode` 2항목 + 핸들러 매핑 |

## 비범위 (YAGNI)

- 정산 취소(cancel) — 별도 이슈/세션
- `payoutReference` 생성 / 외부 송금 연동
- actorId 영속화(감사 로그 테이블)
- 보류·취소 사유 입력
- 멱등 처리(이미 같은 상태로의 재요청은 선행 상태 검증에서 자연히 409로 거부됨)
