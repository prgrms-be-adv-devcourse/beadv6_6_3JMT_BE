# 정산 취소(Settlement Cancel) 설계

작성일: 2026-06-24

## 배경 / 문제

정산 예정 목록에서 관리자가 **취소** 버튼을 누르면 해당 정산이 취소된다.
취소된 정산은 다음 스케줄러 또는 수동 정산으로 **다시 계산**되어야 한다.

이때 두 가지를 분리해서 봐야 한다.

1. **소스 라인 복귀(필수)** — 이 시스템에서 정산 중복을 막는 것은 `Settlement` 레코드가 아니라
   `SettlementSourceLine.settlementId`다. 배치는 `settlementId is null`인 소스 라인만 모아
   `Settlement` 하나를 만들고 그 라인들에 `markSettled(settlementId)` 도장을 찍는다. 따라서
   취소의 본질은 **그 정산이 물고 있던 소스 라인의 `settlementId`를 다시 `null`로 풀어주는 것**이다.
   풀어줘야 다음 정산 실행이 그 라인을 다시 주워 새 `Settlement`를 만든다.
2. **취소 레코드 처리(설계 결정)** — 취소된 `Settlement` 레코드 자체를 목록에서 어떻게 둘지.

## 결정 사항

| 항목 | 결정 |
| --- | --- |
| 취소 레코드 | `settlementStatus = CANCELLED`로 **남긴다**(이력 보존). 삭제하지 않음 |
| 취소 허용 범위 | **지급 완료(`payoutStatus = PAID`) 전까지** 모든 상태에서 취소 가능 |
| 메타데이터 | `canceledAt`(취소 시각)만 기록. 취소 주체·사유는 남기지 않음 |
| 소스 라인 | 취소 시 묶인 소스 라인의 `settlementId`를 `null`로 복귀(필수) |
| 재계산 | 즉시 재계산하지 않음. 풀린 라인을 **다음 스케줄러/수동 정산**이 다시 주워감 |
| 멱등성 | 이미 `CANCELLED`인 건 재취소 시 **409로 거부**(멱등 무시 아님) |

취소 후 같은 판매자·같은 기간에 대해 `CANCELLED` 행 1개가 이력으로 남고, 재계산되면
새 `PENDING_APPROVAL` 행이 **별도로 하나 더** 생긴다. (UI의 "취소 N" 카운트와 일치)

## 설계

### 1. 도메인 계층

#### 1-1. `Settlement.cancel(LocalDateTime canceledAt)` 신설

상태 전이를 도메인 메서드로 표현하고, 불변식을 메서드 안에서 검증한다.

- 검증:
  - `payoutStatus == PAID` → `SettlementAlreadyPaidException`
  - `settlementStatus == CANCELLED` → `SettlementAlreadyCancelledException`
- 통과 시: `settlementStatus = CANCELLED`, `canceledAt = canceledAt`
- `payoutStatus`는 변경하지 않는다. `displayStatus()`가 `settlementStatus == CANCELLED`이면
  `payoutStatus`와 무관하게 `SettlementDisplayStatus.CANCELLED`를 반환하므로 화면 표시는 충분하다.

`canceledAt` 시각은 외부(애플리케이션 서비스)에서 주입한다. (domain-model.md §6 권장 방향)

#### 1-2. `Settlement`에 `canceledAt` 필드 추가

- `LocalDateTime canceledAt`, nullable.
- `BaseEntity`의 `createdAt/updatedAt`과 별개인 **비즈니스 시각**(`confirmedAt`·`paidAt`과 동일한 결).

#### 1-3. `SettlementSourceLine.release(UUID settlementId)` 신설

`markSettled`의 역연산.

- 현재 `this.settlementId`가 인자 `settlementId`와 **일치할 때만** `this.settlementId = null`로 복귀.
- 불일치 시(다른 정산이 물고 있거나 이미 풀린 라인)는 풀지 않는다 — 오풀림 방지. 멱등하게 무시한다.

#### 1-4. 순수 도메인 예외 (`domain/exception`)

`ErrorCode`·`HttpStatus`에 의존하지 않는 순수 `RuntimeException`. (controller-exception.md §2-4)

- `SettlementAlreadyPaidException` — 이미 지급 완료된 정산은 취소 불가
- `SettlementAlreadyCancelledException` — 이미 취소된 정산

### 2. 애플리케이션 계층

#### 2-1. `SettlementUseCase`에 `cancel(UUID settlementId)` 추가

같은 `Settlement` 애그리거트 연산이므로 **별도 UseCase로 분리하지 않고** 기존 포트에 모은다.
(clean-architecture.md §4-1 — 같은 엔티티 연산은 한 `~UseCase`에)

#### 2-2. `SettlementApplicationService.cancel()` 구현 (`@Transactional`)

1. `settlementRepository.findById(settlementId)` → 없으면 `SettlementException(SETTLEMENT_NOT_FOUND)`
2. `settlement.cancel(now)` — 도메인 불변식 검증(위반 시 순수 도메인 예외가 전파됨)
3. 이 정산에 묶인 소스 라인 조회 → 각 `line.release(settlementId)`
   - 소스 라인은 `settlementId == 취소 대상 id`로 조회한다. (아웃바운드 포트에 조회 메서드 신설)
4. 트랜잭션 종료 시 더티체킹으로 `Settlement.canceledAt`·`status`, 소스 라인 `settlementId = null` 반영

> `now`는 서비스에서 생성해 도메인에 주입한다. (테스트 용이성)

#### 2-3. 아웃바운드 포트 보강

소스 라인을 `settlementId`로 조회하는 메서드를 소스 라인 리포지토리 포트에 추가한다.
(예: `findBySettlementId(UUID settlementId)`) — 어댑터는 기존 `~JpaRepository`에 위임.

### 3. 표현 계층

`SettlementController`에 취소 엔드포인트 추가.

```
POST {api.init}/admin/settlements/{settlementId}/cancel
```

- 반환: `SettlementResponse` (취소된 상태 그대로 200으로 내려줌)
- `@Operation`, `@Parameter`(settlementId), `@ApiResponses`:
  - 200 — 취소 성공 (`SettlementResponse`)
  - 404 — 정산 없음 (`ErrorResponse`)
  - 409 — 이미 지급됨 / 이미 취소됨 (`ErrorResponse`)

> 취소는 상태 변경(명령) 흐름이다. 조회 예외(서비스가 `~Response` 직접 반환)에 해당하지 않으므로,
> 명령 흐름 DTO 변환 규칙을 따른다. 다만 이 기능은 반환 형태가 단순하므로 서비스가
> `Settlement`를 반환하고 컨트롤러가 `SettlementResponse.from(...)`으로 변환하는 형태를 기본으로 한다.
> (기존 명령 흐름 컨벤션과 정합하도록 구현 시 `~Result` 유무는 코드 현황에 맞춘다.)

### 4. 예외 매핑 (`global/exception`)

#### 4-1. `SettlementErrorCode` 항목 추가

| 코드 | 메시지 | 상태 |
| --- | --- | --- |
| `SETTLEMENT_NOT_FOUND` | 정산을 찾을 수 없습니다. | 404 NOT_FOUND |
| `SETTLEMENT_ALREADY_PAID` | 이미 지급 완료된 정산은 취소할 수 없습니다. | 409 CONFLICT |
| `SETTLEMENT_ALREADY_CANCELLED` | 이미 취소된 정산입니다. | 409 CONFLICT |

(이미 동일 의미의 코드가 있으면 재사용한다.)

#### 4-2. `GlobalExceptionHandler` 매핑 추가

도메인 순수 예외를 타입별로 잡아 `ErrorCode`로 매핑한다. (controller-exception.md §2-3/§2-4)

- `SettlementAlreadyPaidException` → `SETTLEMENT_ALREADY_PAID`
- `SettlementAlreadyCancelledException` → `SETTLEMENT_ALREADY_CANCELLED`

`SETTLEMENT_NOT_FOUND`는 애플리케이션이 `SettlementException`으로 던지므로 기존 `BusinessException`
핸들러가 처리한다.

### 5. 재계산 흐름 (확인용, 코드 변경 없음)

- 취소가 소스 라인의 `settlementId`를 풀면, 그 라인은 다시 미정산 상태가 된다.
- **다음 스케줄러** 또는 **수동 정산 실행**이 해당 기간을 대상으로 돌 때 `findSettleableSellerIds` /
  `findSettleableLines`(둘 다 `settlementId is null` 조건)로 그 라인을 다시 주워 새 `Settlement`를 만든다.
- 주의: 배치는 특정 정산 기간을 대상으로 실행되므로, **풀린 라인의 기간을 대상으로 하는 정산 실행**이
  있어야 재계산된다. (이미 지난 기간이면 수동 정산으로 그 기간을 재실행)
- 본 설계 범위에서 즉시 자동 재계산은 하지 않는다.

## 테스트 우선 (test-first-unit-test)

구현 전에 단위 테스트를 먼저 작성한다.

**도메인**
- `Settlement.cancel()`
  - `PENDING_APPROVAL` 상태에서 취소 → `CANCELLED`, `canceledAt` 세팅
  - `APPROVED`(payoutStatus `NOT_READY`/`READY`/`PAYOUT_ON_HOLD`)에서 취소 성공
  - `payoutStatus == PAID` → `SettlementAlreadyPaidException`
  - 이미 `CANCELLED` → `SettlementAlreadyCancelledException`
  - `displayStatus()`가 취소 후 `CANCELLED` 반환
- `SettlementSourceLine.release()`
  - `settlementId` 일치 → `null`로 복귀
  - 불일치/이미 `null` → 변화 없음(무시)

**애플리케이션**
- `cancel()` — 정상: 정산 `CANCELLED` + 묶인 소스 라인 전부 `settlementId = null`
- `cancel()` — 없는 정산 → `SettlementException(SETTLEMENT_NOT_FOUND)`
- `cancel()` — PAID 정산 → 도메인 예외 전파
- (통합/시나리오, 선택) 취소 후 재정산 실행 시 동일 라인으로 새 `Settlement` 생성

## 영향 범위 요약

| 계층 | 변경 |
| --- | --- |
| domain/model | `Settlement.cancel()`, `canceledAt` 필드, `SettlementSourceLine.release()` |
| domain/exception | `SettlementAlreadyPaidException`, `SettlementAlreadyCancelledException` 신설 |
| domain/repository | 소스 라인 `findBySettlementId` 포트 메서드 추가 |
| application/usecase | `SettlementUseCase.cancel()` 추가 |
| application/service | `SettlementApplicationService.cancel()` 구현 |
| infrastructure/persistence | 소스 라인 어댑터에 `findBySettlementId` 위임 |
| presentation/controller | `POST .../{settlementId}/cancel` |
| global/exception | `SettlementErrorCode` 항목 + 핸들러 매핑 |

## 비범위 (YAGNI)

- 취소 주체·사유 기록
- 즉시 자동 재계산 트리거
- 지급 완료(PAID) 건 취소(지급금 회수 프로세스)
- 별도 취소 이력 테이블
