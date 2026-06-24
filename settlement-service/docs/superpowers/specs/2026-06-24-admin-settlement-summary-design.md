# 어드민 정산 요약 조회 API 설계

- 이슈: #55
- 엔드포인트: `GET /admin/settlements/summary`
- 작성일: 2026-06-24

## 1. 목적

어드민 정산 관리 화면에서 기준 월의 정산 현황을 표시 상태별로 집계해 한눈에 보여준다.
상태별 정산 금액 합계와 건수를 반환한다.

## 2. 요청 / 응답

### 요청

- 인증 필요, 역할 `ADMIN` (기존 `AdminAuthorizationInterceptor`가 처리)
- Query Parameter

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| `period` | String(`YYYY-MM`) | N | 직전 월(`now - 1개월`) | 조회 기준 월 |

`period` 생략 시 서버가 직전 월로 보정한다. 정산 배치 주기(직전 월 정산)와 일관된다.

### 응답 200

```json
{
  "success": true,
  "data": {
    "period": "2026-05",
    "items": [
      { "status": "PENDING_APPROVAL", "totalAmount": 16200000, "count": 2 },
      { "status": "APPROVED", "totalAmount": 10670000, "count": 2 },
      { "status": "PAYOUT_ON_HOLD", "totalAmount": 2330000, "count": 1 },
      { "status": "PAID", "totalAmount": 19200000, "count": 3 }
    ]
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `period` | String | 조회 기준 월 (`YYYY-MM`) |
| `items[].status` | String | 표시 상태 |
| `items[].totalAmount` | Long | 해당 표시 상태의 `settlementTotalAmount`(지급 순액) 합계 |
| `items[].count` | Integer | 해당 표시 상태의 정산 건수 |

`items`는 해당 기간에 실제 존재하는 표시 상태만 포함한다(0건 상태는 생략).

## 3. 표시 상태(displayStatus) 파생 규칙

도메인 모델 `Settlement`은 `settlementStatus`(4종)와 `payoutStatus`(5종)를 별도 컬럼으로 가진다.
summary의 `status`는 둘을 합친 **통합 표시 상태**다. payout은 정산이 `APPROVED`된 이후에만
진행되므로, `APPROVED`일 때만 `payoutStatus`를 들여다봐 세분화한다.

| settlementStatus | payoutStatus | → 표시 상태 |
|---|---|---|
| `CANCELLED` | (무관) | `CANCELLED` |
| `PENDING_APPROVAL` | (무관) | `PENDING_APPROVAL` |
| `SETTLEMENT_ON_HOLD` | (무관) | `SETTLEMENT_ON_HOLD` |
| `APPROVED` | `NOT_READY` / `READY` | `APPROVED` |
| `APPROVED` | `PAYOUT_REQUESTED` | `PAYOUT_REQUESTED` |
| `APPROVED` | `PAYOUT_ON_HOLD` | `PAYOUT_ON_HOLD` |
| `APPROVED` | `PAID` | `PAID` |

표시 상태 enum 값: `PENDING_APPROVAL`, `SETTLEMENT_ON_HOLD`, `APPROVED`, `PAYOUT_REQUESTED`,
`PAYOUT_ON_HOLD`, `PAID`, `CANCELLED`.

## 4. 집계 방식

DB는 `GROUP BY (settlement_status, payout_status)`로 단순 그룹 집계만 수행하고,
표시 상태 파생·병합은 애플리케이션(자바 도메인 로직)에서 한다. 도메인 규칙이 SQL에 새지 않고,
#56 목록 조회·availableActions에서 같은 파생 로직을 재사용할 수 있다.

```
GET /admin/settlements/summary?period=2026-05
  → Controller (period 바인딩)
  → UseCase.getSummary(period)
  → Service: period 보정 → Repository.aggregateByPeriod(start, end)
  → 집계행(settlementStatus, payoutStatus, sum, count)
  → SettlementDisplayStatus.of(...)로 매핑 → 표시상태별 sum·count 병합
  → SettlementSummaryResult
  → Response.from(result) → ApiResult.success(...)
```

기간 필터는 `periodStart between :start and :end` (해당 월 1일 ~ 말일).

## 5. 컴포넌트 (계층별)

### Domain
- `SettlementDisplayStatus` (`domain/model/enums`) — 표시 상태 enum + `static of(SettlementStatus, PayoutStatus)`.
- `Settlement.displayStatus()` — 인스턴스 메서드, 내부에서 `SettlementDisplayStatus.of(...)` 호출.
- `SettlementStatusAggregate` (`domain/repository`) — 집계 행 record `(SettlementStatus, PayoutStatus, BigDecimal sumSettlementTotal, long count)`.
- `SettlementRepository` 포트에 `List<SettlementStatusAggregate> aggregateByPeriod(LocalDate start, LocalDate end)` 추가.

### Application
- `GetSettlementSummaryUseCase` (인바운드 포트) — `SettlementSummaryResult getSummary(YearMonth period)` (period nullable).
- `SettlementSummaryApplicationService` (구현) — period 보정, 집계 호출, 표시상태 병합.
- `SettlementSummaryResult` (record) — `period(YearMonth)`, `items(List<Item>)`; `Item(SettlementDisplayStatus status, BigDecimal totalAmount, long count)`.

### Infrastructure
- `SettlementJpaRepository` — JPQL 집계 쿼리(`new ...Aggregate(...) ... group by`).
- `SettlementRepositoryAdapter` — JPA 결과를 `SettlementStatusAggregate`로 변환.

### Presentation
- `AdminSettlementController` (신규, `${api.init}/admin/settlements`) — `GET /summary`. `@RequestParam(required=false) YearMonth period`.
- `SettlementSummaryResponse` (record) — `period(String)`, `items`; `Item(String status, long totalAmount, long count)`. `from(SettlementSummaryResult)` 정적 팩토리.

## 6. 예외 / 응답 코드

| 상태 | 설명 |
|------|------|
| `200` | 조회 성공 |
| `400` | `period` 형식 오류(`YYYY-MM` 아님) |
| `401` | 인증 정보 없음 (`UNAUTHENTICATED`) |
| `403` | ADMIN 권한 없음 (`FORBIDDEN`) |

기존 `GlobalExceptionHandler`·`SettlementErrorCode` 매핑을 따른다. 신규 비즈니스 예외는 없다.

## 7. 테스트 (test-first)

- `SettlementDisplayStatus.of(...)` — settlementStatus × payoutStatus 조합별 파생 검증.
- `SettlementSummaryApplicationService`
  - `period` 생략 시 직전 월로 보정.
  - 같은 표시 상태로 묶이는 집계 행의 `sum`·`count` 병합.
  - repository는 mock.

## 8. 확인 필요 (구현 시점)

- `WebConfig`의 인터셉터 등록 경로가 신규 `/admin/settlements/**`(배치 외)도 포함하는지 확인.
  미포함이면 등록 경로를 보강한다.

## 9. 범위 밖 (YAGNI)

- #56 목록 전체 조회는 별도 작업. 본 설계는 요약 조회만 다룬다.
- 표시 상태 필터(`status` 쿼리 파라미터)는 summary에 없다(전체 상태 집계).
