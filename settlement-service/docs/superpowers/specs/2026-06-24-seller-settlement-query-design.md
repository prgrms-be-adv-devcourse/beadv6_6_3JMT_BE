# 판매자 정산 조회 API 설계

관련 이슈: #72(목록 조회), #73(요약 조회), #74(지급 신청 — 이번 범위 제외)

판매자 "내 상점 > 정산 내역" 화면을 채우는 조회 API 두 개를 추가한다. 상단 요약 카드(#73)와
하단 정산 내역 리스트(#72)에 대응한다.

## 범위

- 구현: #72 목록 조회, #73 요약 조회
- 제외: #74 지급 신청(별도 이슈), product 이벤트 컨슈머 실제 처리(골격만, 별도 이슈)

## 계층 구조

어드민 정산(`SettlementController` / `SettlementUseCase`)과 판매자 정산은 권한·응답 필드가
달라 별도로 둔다.

- `presentation/controller/SellerSettlementController` — `${api.init}/sellers/me/settlements`
  - 판매자 식별: 게이트웨이가 주입하는 `X-User-Id` 헤더(UUID)
- `application/usecase/SellerSettlementUseCase` (인바운드 포트)
- `application/service/SellerSettlementApplicationService` (구현, 조회 전용)

조회 흐름이므로 application 서비스가 presentation `~Response`를 직접 만들어 반환한다
(clean-architecture §1/§7 예외 허용). 중간 `~Result`는 두지 않는다.

## #72 목록 조회 — GET /sellers/me/settlements

본인(`seller_id = X-User-Id`) 정산 건만 페이징 조회한다.

### 요청

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| status | SettlementDisplayStatus | N | 전체 | 표시 상태 필터 |
| period | String(YYYY-MM) | N | - | 조회 기준 월 |
| page | int | N | 0 | 0-base 페이지 번호 |
| size | int | N | 10 | 페이지 크기 |
| sort | String | N | periodStart,desc | 정렬 |

`status`는 어드민과 동일하게 `SettlementDisplayStatus` 표시 상태로 받는다.

### 포트 / 리포지토리

`SettlementQueryRepository`에 판매자 필터 페이지 조회를 추가한다.

```
SettlementPage findPageBySeller(UUID sellerId, SettlementDisplayStatus status, YearMonth period, int page, int size);
```

기존 어드민용 `findPage(status, page, size)`는 유지한다.

### 응답 — SellerSettlementListResponse

페이징 묶음(`items`, `totalElements`, `page`, `size`)과 항목(`Item`)으로 구성한다.

| 응답 필드 | 출처 / 산출 |
|---|---|
| settlementId | `settlement.id` |
| period | `periodStart`의 `YYYY-MM` |
| periodStart | `periodStart` |
| periodEnd | `periodEnd` |
| salesCount | `productCount` |
| grossAmount | `totalAmount` |
| feeAmount | `feeTotalAmount` |
| refundAmount | `refundAmount` |
| adjustmentAmount | 도메인 필드 없음 → `0` 고정(추후 확장) |
| payoutAmount | `settlementTotalAmount` |
| status | `displayStatus().name()` |
| displayStatus | `displayStatus()` (한글 라벨) |
| availableActions | `displayStatus == APPROVED`이면 `[REQUEST_PAYOUT]`, 아니면 `[]` |

`payoutAmount = grossAmount - feeAmount - refundAmount - adjustmentAmount` 관계는
이미 `settlementTotalAmount`에 반영돼 있다고 보고 별도 재계산하지 않는다.

`availableActions` 항목은 `{ type, label }` 형태다. 현재는 `REQUEST_PAYOUT`("지급 신청하기")
하나만 노출한다. 실제 지급 신청 처리는 #74에서 다룬다.

## #73 요약 조회 — GET /sellers/me/settlements/summary

본인 누적 지표 4개를 반환한다.

### 응답 — SellerSettlementSummaryResponse

| 필드 | 타입 | 산출 |
|---|---|---|
| registeredPromptCount | int | `0` 고정 — product 이벤트 컨슈머 별도 이슈 |
| totalSalesCount | long | 본인 `settlement_source_line` PAID 건수 |
| totalRevenueAmount | long(BigDecimal) | 본인 `settlement_source_line` 거래액 합 |
| totalSettlementAmount | long(BigDecimal) | 본인 settlement 중 지급완료(PAID) `settlementTotalAmount` 합 |

### 포트 / 리포지토리

- `SettlementSourceRepository`에 판매자 집계 추가:
  - `long countPaidBySeller(UUID sellerId)` — 누적 판매 건수
  - `BigDecimal sumPaidAmountBySeller(UUID sellerId)` — 누적 거래액
- `SettlementRepository`(또는 query 리포지토리)에 지급완료 합계 추가:
  - `BigDecimal sumPaidSettlementAmountBySeller(UUID sellerId)`

`registeredPromptCount`는 정산 서비스가 가진 데이터로 산출할 수 없다(상품 등록 수는 product
서비스 소관). product 이벤트를 수신해 채우기로 하되, 이번엔 값을 `0`으로 두고 컨슈머 골격만 만든다.

## product 이벤트 컨슈머 골격

`infrastructure/event/`에 product 프롬프트 등록/삭제 이벤트 리스너 골격을 만든다.

- 빈 핸들러 메서드 + `// TODO` 주석으로 "판매자별 등록 프롬프트 수 집계 → registeredPromptCount"
  채울 위치를 표시한다.
- 실제 수신 처리·저장소·요약 반영은 별도 이슈에서 구현한다.

## 테스트

정산은 데이터 오류 영향이 크므로 `test-first-unit-test` 스킬로 도메인/애플리케이션 단위
테스트를 먼저 작성한다.

- 도메인: `availableActions` 노출 규칙(APPROVED일 때만 REQUEST_PAYOUT), `displayStatus` 매핑
- 애플리케이션: 목록 조회(sellerId 필터·페이징), 요약 집계(4개 필드 산출, registeredPromptCount=0)

## 미결정 / 추후 과제

- `adjustmentAmount` 실제 산출 — 현재 도메인에 기타 조정 금액 개념이 없어 0 고정
- `registeredPromptCount` — product 이벤트 컨슈머(별도 이슈)
- #74 지급 신청 전이 처리 — 엔드포인트 경로 확정 후 구현
