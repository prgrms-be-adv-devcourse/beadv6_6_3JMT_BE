# 정산 서비스 API 연동 가이드 (프론트엔드용)

정산 서비스(settlement-service)의 REST API 스펙. 프론트엔드 연동에 필요한 엔드포인트,
요청/응답 구조, 인증 헤더, 상태값, 에러 코드를 정리한다.

> 이 문서는 실제 컨트롤러·DTO·enum 코드를 기준으로 작성됐다. 백엔드 코드가 바뀌면 같이 갱신한다.

> **파이널 변경 예정 (아직 미적용 — 현재 스펙은 아래 그대로 유효)**
>
> - 관리자 API(§2-A `/admin/settlements`, §2-C `/admin/settlements/batch`)는 어드민 모듈
>   (admin-service)로 이관 예정이다. 이관되면 base URL·prefix 가 admin-service 기준으로 바뀐다.
>   판매자 API(§2-B)는 **유저(셀러) 모듈로 이관 예정**이다(#236 — 운영 단일 진실 seller_settlement).
>   (`architecture/admin-module-separation.md`, `trade-offs/seller-settlement-separation.md`)
> - **상태 모델이 이중상태(`SettlementStatus`×`PayoutStatus`)에서 `SettlementDisplayStatus` 7값
>   단일로 바뀔 예정이다.** 이관 후 어드민·셀러 응답은 표시상태(7값) 하나만 노출하고, 아래에
>   서술된 raw 이중상태(`settlementStatus`/`payoutStatus`) 조합·노출은 정리된다.
>   (`trade-offs/seller-settlement-separation.md`)
> - 배치잡 수동 실행(C-1)은 **즉시 실행 → 시간 지정 예약 실행**으로 바뀔 예정이다. 예약
>   접수·상태 조회는 admin-service 의 새 API 가 맡고(요청에 실행 시각 추가), 기존 즉시 실행
>   API 는 배치 테스트용으로 정산에 남는다. 설계 후 이 문서에 반영한다. (`final-roadmap.md` §2)

---

## 0. 기본 정보

| 항목 | 값 |
| --- | --- |
| Base URL (로컬) | `http://localhost:8080` |
| API prefix | `/api/v1` |
| 전체 prefix 예시 | `http://localhost:8080/api/v1/admin/settlements` |
| 응답 형식 | JSON |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |

> 실제 운영에서는 **API 게이트웨이**를 거친다. base URL은 게이트웨이 주소로 바뀌고,
> 게이트웨이가 라우팅과 인증 헤더 주입을 담당한다. 프론트는 게이트웨이 주소만 알면 된다.

### 인증 헤더 (중요)

이 서비스는 **게이트웨이가 주입하는 헤더로 인증·인가를 처리**한다. 토큰 검증은 게이트웨이가 하고,
서비스는 아래 헤더를 신뢰한다.

| 헤더 | 의미 | 값 |
| --- | --- | --- |
| `X-User-Id` | 사용자 ID | UUID |
| `X-User-Role` | 사용자 역할 | `ADMIN` / `SELLER` 등 |

- **관리자 API**(`/admin/...`)는 `X-User-Role: ADMIN` 필요
- **판매자 API**(`/sellers/me/...`)는 `X-User-Role: SELLER` 필요, `X-User-Id`로 본인 정산만 조회
- 로컬에서 게이트웨이 없이 직접 테스트할 땐 이 헤더를 **직접 넣어** 호출한다.
  (Swagger UI는 우측 상단 Authorize에 `X-User-Id`, `X-User-Role`을 입력하면 자동 적용)

---

## 1. 상태값(enum) 정의

프론트에서 필터·뱃지·액션 분기에 쓰는 상태값. **API가 주고받는 건 대부분 `displayStatus`(표시 상태)**다.

### SettlementDisplayStatus — 표시 상태 (프론트가 주로 쓰는 값)

목록 응답의 `displayStatus`, 목록 조회 `status` 쿼리 파라미터에 쓰는 값이다.

| 코드 | 한글 라벨 | 의미 |
| --- | --- | --- |
| `WAITING` | 대기 | 승인 대기 |
| `APPROVAL_ON_HOLD` | 승인 보류 | 승인 보류됨 |
| `APPROVED` | 승인 | 승인 완료(지급 준비) |
| `PAYOUT_REQUESTED` | 지급 신청 | 판매자가 지급 신청함 |
| `PAYOUT_ON_HOLD` | 지급 보류 | 지급 보류됨 |
| `PAID` | 지급 완료 | 지급 완료 |
| `CANCELLED` | 취소 | 정산 취소됨 |

### SettlementStatus — 내부 정산 상태 (상태 변경 응답에 포함)

| 코드 | 의미 |
| --- | --- |
| `PENDING_APPROVAL` | 승인 대기 |
| `SETTLEMENT_ON_HOLD` | 승인 보류 |
| `APPROVED` | 승인 완료 |
| `CANCELLED` | 취소 |

### PayoutStatus — 지급 상태 (상태 변경 응답에 포함)

| 코드 | 의미 |
| --- | --- |
| `NOT_READY` | 지급 미준비 |
| `READY` | 지급 준비 |
| `PAYOUT_REQUESTED` | 지급 신청됨 |
| `PAYOUT_ON_HOLD` | 지급 보류 |
| `PAID` | 지급 완료 |

> `displayStatus`는 `SettlementStatus` + `PayoutStatus` 조합으로 결정된다(백엔드가 계산해 내려줌).
> 프론트는 보통 `displayStatus`만 보면 된다. `settlementStatus`/`payoutStatus`는 상태 변경 PATCH 응답에서 참고용으로 내려온다.

---

## 2. 엔드포인트 목록

### A. 관리자 정산 관리 — `/api/v1/admin/settlements`

`X-User-Role: ADMIN` 필요.

| 메서드 | 경로 | 설명 | 응답 |
| --- | --- | --- | --- |
| GET | `/summary` | 정산 요약 카드(상태별 합계·건수) | `SettlementSummaryResponse` |
| GET | `/` | 정산 목록(상태 필터·페이징) | `SettlementListResponse` |
| PATCH | `/{settlementId}/approve` | 정산 승인 (대기→승인) | `SettlementStatusResponse` |
| PATCH | `/{settlementId}/hold` | 승인 보류 (대기→승인보류) | `SettlementStatusResponse` |
| PATCH | `/{settlementId}/release-hold` | 승인 보류 해제 (보류→대기) | `SettlementStatusResponse` |
| PATCH | `/{settlementId}/payout` | 지급 처리 (준비→지급완료) | `SettlementStatusResponse` |
| PATCH | `/{settlementId}/payout-hold` | 지급 보류 (준비→지급보류) | `SettlementStatusResponse` |
| PATCH | `/{settlementId}/payout-hold/release` | 지급 보류 해제 (지급보류→준비) | `SettlementStatusResponse` |
| PATCH | `/{settlementId}/cancel` | 정산 취소 (지급완료 전) | `SettlementResponse` |

- 상태 변경 PATCH들은 **요청 바디 없음**. `settlementId`(경로)와 `X-User-Id`(헤더)만 보낸다.

### B. 판매자 정산 조회 — `/api/v1/sellers/me/settlements`

`X-User-Role: SELLER` 필요. `X-User-Id`로 본인 정산만.

| 메서드 | 경로 | 설명 | 응답 |
| --- | --- | --- | --- |
| GET | `/summary` | 내 상점 요약 지표 | `SellerSettlementSummaryResponse` |
| GET | `/` | 본인 정산 내역(상태·월 필터·페이징) | `SellerSettlementListResponse` |
| PATCH | `/{settlementId}/payout-request` | 지급 신청 (승인완료→지급신청) | `SettlementStatusResponse` |

### C. 정산 배치잡(관리자) — `/api/v1/admin/settlements/batch`

`X-User-Role: ADMIN` 필요.

| 메서드 | 경로 | 설명 | 응답 |
| --- | --- | --- | --- |
| POST | `/` | 정산 배치잡 **비동기** 실행 접수 | `202` + `SettlementJobResponse` |
| GET | `/{jobExecutionId}` | 배치잡 상태 조회(폴링용) | `SettlementJobStatusResponse` |

> 배치 실행은 비동기(202)다. POST로 `jobExecutionId`를 받고, GET으로 폴링해
> `status`가 `COMPLETED`/`FAILED`가 될 때까지 확인한다.

---

## 3. 요청/응답 상세

타입은 TypeScript interface로 표기한다. (`UUID`/`LocalDate`/`LocalDateTime`/`YearMonth`는 모두 JSON에선 `string`)

```ts
type UUID = string;            // "550e8400-e29b-41d4-a716-446655440000"
type LocalDate = string;       // "2026-06-30"
type LocalDateTime = string;   // "2026-06-24T09:00:00"
type Decimal = string;         // BigDecimal → 정밀도 보존 위해 문자열로 옴 "459000.00"
```

> **금액(BigDecimal)은 JSON에서 문자열로 직렬화**된다(예: `"459000.00"`). 계산 시 `Number()` 변환에 주의.

---

### A-1. 정산 요약 카드 — `GET /admin/settlements/summary`

요청: 헤더만 (`X-User-Id`, `X-User-Role: ADMIN`)

```ts
interface SettlementSummaryResponse {
  cards: {
    status: string;        // SettlementDisplayStatus 코드 (예: "WAITING")
    totalAmount: Decimal;  // 해당 상태 지급액 합계 (예: "1135500.00")
    count: number;         // 건수 (예: 4)
  }[];
}
```

### A-2. 정산 목록 — `GET /admin/settlements`

쿼리 파라미터:

| 파라미터 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `status` | SettlementDisplayStatus | N | (전체) | 표시 상태 필터 |
| `page` | number | N | `0` | 0-base 페이지 |
| `size` | number | N | `20` | 페이지 크기 |

```ts
interface SettlementListResponse {
  items: {
    settlementId: UUID;
    sellerId: UUID;
    sellerName: string | null;        // User 서비스 동기 조회로 채움(없으면 null)
    periodStart: LocalDate;           // "2026-06-01"
    periodEnd: LocalDate;             // "2026-06-30"
    productCount: number;             // 판매 건수
    totalAmount: Decimal;             // 총 거래액
    feeTotalAmount: Decimal;          // 수수료
    settlementTotalAmount: Decimal;   // 지급액
    displayStatus: string;            // SettlementDisplayStatus 코드
  }[];
  totalElements: number;
  page: number;
  size: number;
}
```

### A-3. 정산 상태 변경 PATCH (approve/hold/release-hold/payout/payout-hold/payout-hold/release)

요청: 경로 `settlementId` + 헤더(`X-User-Id`, `X-User-Role: ADMIN`). **바디 없음.**

```ts
interface SettlementStatusResponse {
  settlementId: UUID;
  settlementStatus: string;   // SettlementStatus (예: "APPROVED")
  payoutStatus: string;       // PayoutStatus (예: "READY")
  displayStatus: string;      // SettlementDisplayStatus (예: "APPROVED")
  confirmedAt: LocalDateTime | null;
  paidAt: LocalDateTime | null;
  payoutReference: string | null;
  failureReason: string | null;
  updatedAt: LocalDateTime | null;
}
```

### A-4. 정산 취소 — `PATCH /admin/settlements/{settlementId}/cancel`

```ts
interface SettlementResponse {
  settlementId: UUID;
  sellerId: UUID;
  displayStatus: string;          // "CANCELLED"
  canceledAt: LocalDateTime | null;
}
```

---

### B-1. 판매자 요약 — `GET /sellers/me/settlements/summary`

요청: 헤더만 (`X-User-Id`, `X-User-Role: SELLER`)

```ts
interface SellerSettlementSummaryResponse {
  registeredPromptCount: number;   // 등록한 프롬프트 수
  totalSalesCount: number;         // 누적 판매 건수
  totalRevenueAmount: Decimal;     // 누적 총 거래액
  totalSettlementAmount: Decimal;  // 누적 지급 완료 금액
}
```

### B-2. 판매자 정산 내역 — `GET /sellers/me/settlements`

쿼리 파라미터:

| 파라미터 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `status` | SettlementDisplayStatus | N | (전체) | 표시 상태 필터 |
| `period` | string (`yyyy-MM`) | N | (전체) | 기준 월 (예: `2026-06`) |
| `page` | number | N | `0` | 0-base 페이지 |
| `size` | number | N | `10` | 페이지 크기 |

```ts
interface SellerSettlementListResponse {
  items: {
    settlementId: UUID;
    period: string;             // "2026-06"
    periodStart: LocalDate;
    periodEnd: LocalDate;
    salesCount: number;
    grossAmount: Decimal;       // 총 거래액
    feeAmount: Decimal;         // 판매 수수료
    refundAmount: Decimal;      // 환불 차감액
    adjustmentAmount: Decimal;  // 기타 조정(현재 미사용, "0")
    payoutAmount: Decimal;      // 최종 지급 예정/완료 금액
    status: string;             // SettlementDisplayStatus 코드 (예: "WAITING")
    displayStatus: string;      // 한글 라벨 (예: "대기")
    availableActions: {         // 현재 상태에서 가능한 액션 (0~1개)
      type: string;             // 현재 구현: "REQUEST_PAYOUT"만
      label: string;            // "지급 신청하기"
    }[];
  }[];
  totalElements: number;
  page: number;
  size: number;
}
```

> 주의: 이 응답의 `status`는 코드, `displayStatus`는 **한글 라벨**이다(관리자 목록과 의미가 반대이니 주의).
> `availableActions`는 정산이 `APPROVED`(승인 완료)일 때만 `REQUEST_PAYOUT` 하나가 들어오고, 그 외엔 빈 배열.

### B-3. 지급 신청 — `PATCH /sellers/me/settlements/{settlementId}/payout-request`

요청: 경로 `settlementId` + 헤더(`X-User-Id`, `X-User-Role: SELLER`). 바디 없음.
응답: `SettlementStatusResponse` (A-3와 동일 구조)

---

### C-1. 배치잡 실행 — `POST /admin/settlements/batch`

요청 바디:

```ts
interface RunSettlementJobRequest {
  period: string;   // YearMonth "yyyy-MM" (필수), 예: "2026-06"
}
```

응답: `202 Accepted`

```ts
interface SettlementJobResponse {
  jobExecutionId: number;     // 이후 상태 조회에 사용
  jobName: string;            // "settlementJob"
  status: string;            // 접수 시점 "STARTING"/"STARTED"
  startTime: LocalDateTime;
}
```

### C-2. 배치잡 상태 조회 — `GET /admin/settlements/batch/{jobExecutionId}`

```ts
interface SettlementJobStatusResponse {
  jobExecutionId: number;
  jobName: string;
  status: string;             // STARTING/STARTED/COMPLETED/FAILED/STOPPED
  exitCode: string;           // COMPLETED/FAILED, 실행 중엔 "UNKNOWN"
  startTime: LocalDateTime;
  endTime: LocalDateTime | null;  // 실행 중이면 null
  failureMessage: string | null; // 실패 시에만
}
```

폴링 패턴: POST로 받은 `jobExecutionId`로 이 API를 주기 호출 →
`status === "COMPLETED"`(성공) 또는 `"FAILED"`(실패) 나오면 중단.

---

## 4. 에러 응답

비즈니스 예외는 공통 `ErrorResponse` 형식으로 내려온다.

```ts
interface ErrorResponse {
  code: string;       // 예: "S-010"
  message: string;    // 예: "정산을 찾을 수 없습니다."
  // (common-module ErrorResponse 구조에 따라 timestamp 등 추가 필드가 있을 수 있음)
}
```

### 에러 코드표

| Code | HTTP | 메시지 |
| --- | --- | --- |
| S-001 | 404 | 정산 배치를 찾을 수 없습니다. |
| S-002 | 500 | 정산 배치 잡 실행에 실패했습니다. |
| S-003 | 400 | 요청 값이 올바르지 않습니다. |
| S-004 | 500 | 예상하지 못한 서버 오류가 발생했습니다. |
| S-005 | 401 | 인증 정보가 없습니다. |
| S-006 | 403 | 접근 권한이 없습니다. |
| S-007 | 409 | 정산 배치가 처리 중 상태가 아닙니다. |
| S-008 | 404 | 정산 배치 잡 실행 이력을 찾을 수 없습니다. |
| S-009 | 409 | 이미 정산에 포함된 소스 라인입니다. |
| S-010 | 404 | 정산을 찾을 수 없습니다. |
| S-011 | 409 | 이미 지급 완료된 정산은 취소할 수 없습니다. |
| S-012 | 409 | 이미 취소된 정산입니다. |
| S-013 | 409 | 현재 상태에서 변경할 수 없는 정산입니다. |
| S-014 | 403 | 본인 정산이 아닙니다. |
| S-016 | 500 | 정산 이벤트 메시지 역직렬화에 실패했습니다. |

### 엔드포인트별 주요 에러

| 상황 | HTTP | 비고 |
| --- | --- | --- |
| 인증 헤더 없음 | 401 | S-005 |
| 권한 부족(역할 불일치) | 403 | S-006 / 판매자 본인 아님 S-014 |
| 정산 ID 없음 | 404 | S-010 |
| 잡 실행 이력 없음 | 404 | S-008 |
| 상태 전이 불가(이미 처리됨 등) | 409 | S-011/S-012/S-013 |
| 요청 값 검증 실패 | 400 | S-003 |

---

## 5. 프론트 연동 체크리스트

- [ ] base URL을 환경변수로 분리 (`VITE_API_BASE_URL` 등), prefix `/api/v1` 포함 여부 결정
- [ ] 게이트웨이 경유 여부 확정 — 직접 호출이면 `X-User-Id`/`X-User-Role` 헤더를 프론트가 넣어야 함
- [ ] 금액 필드(BigDecimal=string) 표시·계산 시 숫자 변환 처리
- [ ] 목록 페이징은 0-base (`page=0`부터)
- [ ] 상태 필터는 `SettlementDisplayStatus` 코드값 사용
- [ ] 배치 실행은 비동기 → 폴링 로직 필요
- [ ] 에러는 `code`(S-0xx)로 분기, `message`는 사용자 노출 가능
- [ ] CORS: 직접 호출 시 백엔드에 CORS 설정이 필요할 수 있음(현재 서비스에 별도 CORS 설정 없음 — 게이트웨이 전제)
