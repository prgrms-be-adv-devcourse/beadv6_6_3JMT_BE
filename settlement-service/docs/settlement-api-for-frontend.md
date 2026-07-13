# 정산 서비스 API 연동 가이드 (프론트엔드용)

정산 서비스(settlement-service)의 REST API 스펙. 프론트엔드 연동에 필요한 엔드포인트,
요청/응답 구조, 인증 헤더, 상태값, 에러 코드를 정리한다.

> 이 문서는 실제 컨트롤러·DTO·enum 코드를 기준으로 작성됐다. 백엔드 코드가 바뀌면 같이 갱신한다.

> **범위: 정산 배치 API 만.** 어드민 정산 관리 API(`/admin/settlements` 목록·요약·승인·보류·지급·취소)는
> **admin-service** 로, 판매자 정산 API(`/sellers/me/settlements` 조회·지급신청)는 **user-service(셀러
> 모듈)** 로 **이관 완료**됐다(#234·#236). 두 API 스펙은 각 서비스 저장소의 프론트 가이드를 본다.
> 현재 settlement-service `presentation` 에 남은 컨트롤러는 **`SettlementBatchController` 하나**이고,
> 이 문서도 그 배치 API 만 다룬다.
> (이관 배경: `architecture/admin-module-separation.md`, `trade-offs/seller-settlement-separation.md`)

---

## 0. 기본 정보

| 항목 | 값 |
| --- | --- |
| Base URL (로컬) | `http://localhost:8080` |
| API prefix | `/api/v1` |
| 전체 prefix 예시 | `http://localhost:8080/api/v1/admin/settlements/batch` |
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
| `X-User-Role` | 사용자 역할 | `ADMIN` |

- 배치 API(`/admin/settlements/batch/...`)는 `X-User-Role: ADMIN` 필요.
- 로컬에서 게이트웨이 없이 직접 테스트할 땐 이 헤더를 **직접 넣어** 호출한다.
  (Swagger UI는 우측 상단 Authorize에 `X-User-Id`, `X-User-Role`을 입력하면 자동 적용)

---

## 1. 엔드포인트 — 정산 배치잡 (관리자)

`/api/v1/admin/settlements/batch` · `X-User-Role: ADMIN` 필요.

| 메서드 | 경로 | 설명 | 응답 |
| --- | --- | --- | --- |
| POST | `/` | 정산 배치잡 **비동기** 실행 접수 | `202` + `SettlementJobResponse` |
| GET | `/{jobExecutionId}` | 배치잡 상태 조회(폴링용) | `200` + `SettlementJobStatusResponse` |

> 배치 실행은 비동기(202)다. POST로 `jobExecutionId`를 받고, GET으로 폴링해
> `status`가 `COMPLETED`/`FAILED`가 될 때까지 확인한다.
>
> 배치는 실행 시점에 그 기간의 결제·환불 라인을 order 에서 gRPC pull 로 당겨 적재한 뒤
> 판매자·기간 단위로 정산을 계산한다. (원천 수급 배경: `trade-offs/order-data-sourcing.md`)

### 1-1. 배치잡 실행 — `POST /admin/settlements/batch`

타입은 TypeScript interface로 표기한다. (`LocalDateTime`은 JSON에선 `string`)

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
  status: string;             // 접수 시점 "STARTING"/"STARTED"
  startTime: string;          // LocalDateTime "2026-06-24T09:00:00"
}
```

### 1-2. 배치잡 상태 조회 — `GET /admin/settlements/batch/{jobExecutionId}`

```ts
interface SettlementJobStatusResponse {
  jobExecutionId: number;
  jobName: string;
  status: string;             // STARTING/STARTED/COMPLETED/FAILED/STOPPED
  exitCode: string;           // COMPLETED/FAILED, 실행 중엔 "UNKNOWN"
  startTime: string;          // LocalDateTime
  endTime: string | null;     // 실행 중이면 null
  failureMessage: string | null; // 실패 시에만
}
```

폴링 패턴: POST로 받은 `jobExecutionId`로 이 API를 주기 호출 →
`status === "COMPLETED"`(성공) 또는 `"FAILED"`(실패) 나오면 중단.

---

## 2. 에러 응답

비즈니스 예외는 공통 `ErrorResponse` 형식으로 내려온다.

```ts
interface ErrorResponse {
  code: string;       // 예: "S-002"
  message: string;    // 예: "정산 배치 잡 실행에 실패했습니다."
  // (common-module ErrorResponse 구조에 따라 timestamp 등 추가 필드가 있을 수 있음)
}
```

### 에러 코드표 (배치 API 관련)

`SettlementErrorCode` 기준. 배치 API 프론트가 마주칠 수 있는 코드만 추린다.

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
| S-017 | 500 | 정산 대상 라인 조회에 실패했습니다. (order gRPC pull 실패 → 배치 실패) |

> 참고: `SettlementErrorCode` 에는 소스 라인·이벤트 발행/역직렬화용 코드(S-009·S-015·S-016 등)도
> 있으나, 이는 배치 내부·Kafka 처리용이라 배치 REST 응답으로 프론트에 직접 노출되는 경로는 아니다.

### 엔드포인트별 주요 에러

| 상황 | HTTP | 비고 |
| --- | --- | --- |
| 인증 헤더 없음 | 401 | S-005 |
| 권한 부족(ADMIN 아님) | 403 | S-006 |
| 요청 값 검증 실패(`period` 형식 오류 등) | 400 | S-003 |
| 잡 실행 이력 없음(잘못된 `jobExecutionId`) | 404 | S-008 |
| 배치 상태 전이 불가 | 409 | S-007 |
| 잡 실행 실패 / 원천 라인 조회 실패 | 500 | S-002 / S-017 |

---

## 3. 프론트 연동 체크리스트

- [ ] base URL을 환경변수로 분리 (`VITE_API_BASE_URL` 등), prefix `/api/v1` 포함 여부 결정
- [ ] 게이트웨이 경유 여부 확정 — 직접 호출이면 `X-User-Id`/`X-User-Role: ADMIN` 헤더를 프론트가 넣어야 함
- [ ] 배치 실행은 비동기(202) → `jobExecutionId`로 상태 폴링 로직 필요
- [ ] 폴링 종료 조건은 `status`가 `COMPLETED`/`FAILED`
- [ ] 에러는 `code`(S-0xx)로 분기, `message`는 사용자 노출 가능
- [ ] CORS: 직접 호출 시 백엔드에 CORS 설정이 필요할 수 있음(현재 서비스에 별도 CORS 설정 없음 — 게이트웨이 전제)
- [ ] 어드민 정산 관리·판매자 정산 화면은 이 서비스가 아니라 **admin-service / user-service** API를 연동
