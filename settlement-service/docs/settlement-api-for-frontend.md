# 정산 서비스 API 연동 가이드 (프론트엔드용)

정산 서비스(settlement-service)의 REST API 스펙. 프론트엔드 연동에 필요한 엔드포인트,
요청/응답 구조, 인증 헤더, 에러 코드를 정리한다.

> 이 문서는 실제 컨트롤러·DTO·enum 코드를 기준으로 작성됐다. 백엔드 코드가 바뀌면 같이 갱신한다.

> **모듈 이관 반영 (2026-07)** — 예전 이 문서에 있던 API 대부분이 다른 모듈로 이관·정리됐다.
>
> - **관리자 정산 관리**(`/admin/settlements` 요약·목록·상태 변경 PATCH)는 **admin-service** 로
>   이관됐다(#234·게이트웨이 재지정 #250). 스펙은 admin-service 문서를 본다.
> - **판매자 정산 조회**(`/sellers/me/settlements`)는 **user-service** 셀러 정산으로 이관됐다
>   (#236 — 운영 단일 진실 `seller_settlement`). 스펙은 user-service 문서를 본다.
> - 이관에 따라 정산 서비스의 상태 모델(`SettlementStatus`×`PayoutStatus` 이중상태·
>   `SettlementDisplayStatus` 표시상태)도 제거됐다(#254). 정산 서비스의 `Settlement`는 이제
>   **순수 계산 로그**(금액·기간·상세)만 담고, 승인·지급 등 운영 상태는 이관된 각 모듈이 관리한다.
> - 정산 서비스에 남은 프론트 연동 API는 **정산 배치잡 실행/상태 조회** 두 개다. (배치잡 수동 실행은
>   추후 **즉시 실행 → 시간 지정 예약 실행**으로 바뀔 수 있다 — 예약 접수는 admin-service 의 새 API 가
>   맡고 기존 즉시 실행 API 는 배치 테스트용으로 남는 방향. `final-roadmap.md` §2)

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
| `X-User-Role` | 사용자 역할 | `ADMIN` / `SELLER` 등 |

- 배치잡 API(`/admin/settlements/batch`)는 `X-User-Role: ADMIN` 필요
- 로컬에서 게이트웨이 없이 직접 테스트할 땐 이 헤더를 **직접 넣어** 호출한다.
  (Swagger UI는 우측 상단 Authorize에 `X-User-Id`, `X-User-Role`을 입력하면 자동 적용)

### 성공 응답 래퍼 — `ApiResult<T>`

성공 응답 본문은 common-module 의 `ApiResult` 로 감싸져 내려온다. 아래 상세의 응답 타입은
전부 `data` 필드 안에 들어가는 형태다.

```ts
interface ApiResult<T> {
  success: true;
  data: T;
  message: "success";
}
```

---

## 1. 엔드포인트 목록

### 정산 배치잡(관리자) — `/api/v1/admin/settlements/batch`

`X-User-Role: ADMIN` 필요.

| 메서드 | 경로 | 설명 | 응답 |
| --- | --- | --- | --- |
| POST | `/` | 정산 배치잡 **비동기** 실행 접수 | `202` + `ApiResult<SettlementJobResponse>` |
| GET | `/{jobExecutionId}` | 배치잡 상태 조회(폴링용) | `200` + `ApiResult<SettlementJobStatusResponse>` |

> 배치 실행은 비동기(202)다. POST로 `jobExecutionId`를 받고, GET으로 폴링해
> `status`가 `COMPLETED`/`FAILED`가 될 때까지 확인한다.

---

## 2. 요청/응답 상세

타입은 TypeScript interface로 표기한다. (`LocalDateTime`/`YearMonth`는 JSON에선 `string`)

```ts
type LocalDateTime = string;   // "2026-06-24T09:00:00"
```

### 2-1. 배치잡 실행 — `POST /admin/settlements/batch`

요청: 헤더(`X-User-Id`, `X-User-Role: ADMIN`) + 바디

```ts
interface RunSettlementJobRequest {
  period: string;   // YearMonth "yyyy-MM" (필수), 예: "2026-06"
}
```

응답: `202 Accepted`, `ApiResult<SettlementJobResponse>`

```ts
interface SettlementJobResponse {
  jobExecutionId: number;     // 이후 상태 조회에 사용
  jobName: string;            // "settlementJob"
  status: string;             // 접수 시점 "STARTING"/"STARTED"
  startTime: LocalDateTime;
}
```

### 2-2. 배치잡 상태 조회 — `GET /admin/settlements/batch/{jobExecutionId}`

요청: 경로 `jobExecutionId` + 헤더(`X-User-Id`, `X-User-Role: ADMIN`)

응답: `200 OK`, `ApiResult<SettlementJobStatusResponse>`

```ts
interface SettlementJobStatusResponse {
  jobExecutionId: number;
  jobName: string;
  status: string;                 // STARTING/STARTED/COMPLETED/FAILED/STOPPED
  exitCode: string;               // COMPLETED/FAILED, 실행 중엔 "UNKNOWN"
  startTime: LocalDateTime;
  endTime: LocalDateTime | null;  // 실행 중이면 null
  failureMessage: string | null;  // 실패 시에만
}
```

폴링 패턴: POST로 받은 `jobExecutionId`로 이 API를 주기 호출 →
`status === "COMPLETED"`(성공) 또는 `"FAILED"`(실패) 나오면 중단.

---

## 3. 에러 응답

비즈니스 예외는 공통 `ErrorResponse` 형식으로 내려온다.

```ts
interface ErrorResponse {
  success: false;
  data: null;
  message: string;    // 예: "정산 배치 잡 실행 이력을 찾을 수 없습니다."
  code: string;       // 예: "S-008"
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
| S-016 | 500 | 정산 이벤트 메시지 역직렬화에 실패했습니다. (Kafka 소비 내부용 — REST 응답으로는 안 나감) |

### 엔드포인트별 주요 에러

| 상황 | HTTP | 비고 |
| --- | --- | --- |
| 인증 헤더 없음 | 401 | S-005 |
| 권한 부족(역할 불일치) | 403 | S-006 |
| 잡 실행 이력 없음 | 404 | S-008 |
| 요청 값 검증 실패(`period` 누락 등) | 400 | S-003 |
| 잡 실행 실패 | 500 | S-002 |

---

## 4. 프론트 연동 체크리스트

- [ ] base URL을 환경변수로 분리 (`VITE_API_BASE_URL` 등), prefix `/api/v1` 포함 여부 결정
- [ ] 게이트웨이 경유 여부 확정 — 직접 호출이면 `X-User-Id`/`X-User-Role` 헤더를 프론트가 넣어야 함
- [ ] 성공 응답은 `ApiResult` 래퍼 — 실제 데이터는 `data` 필드에서 꺼낸다
- [ ] 배치 실행은 비동기(202) → 폴링 로직 필요
- [ ] 에러는 `code`(S-0xx)로 분기, `message`는 사용자 노출 가능
- [ ] 관리자 정산 관리·판매자 정산 조회 화면은 이 서비스가 아니라 admin-service·user-service API 를 본다
- [ ] CORS: 직접 호출 시 백엔드에 CORS 설정이 필요할 수 있음(현재 서비스에 별도 CORS 설정 없음 — 게이트웨이 전제)
