# Settlement Service API

**Base:** `http://localhost:8080/api/v2`

> 정산 공개 API는 `#305 (이슈)`에서 `/api/v2`로 전환했다. 기존 `/api/v1` 경로는 제공하지 않는다.

> ⚠ 셀러·어드민 정산 API는 `#233`, `#234`(커밋 `395563d9`)에서 settlement-service 밖으로 이관됐다.
> 이관 후 settlement-service에 남은 것은 관리자 정산 배치 실행/상태 조회 API뿐이다.
> - 셀러 정산 조회 API 명세는 `docs/api-spec/user.md` 참고.
> - 관리자 정산 관리 API 명세는 `docs/api-spec/admin.md` 참고.

## 공통 사항

- 인증이 필요한 엔드포인트는 클라이언트가 `Authorization: Bearer {accessToken}` 헤더로 API Gateway 를 호출
- 토큰 검증은 API Gateway에서 수행. 각 서비스는 게이트웨이가 주입하는 헤더(`X-User-Id`, `X-User-Role`)만 읽음
- 모든 응답은 공통 래퍼 `{ "success", "data", "message" }` 로 감싼다. 실패 시 `data` 는 `null`, `code`(예: `S-013`)가 함께 내려간다
- 금액(`BigDecimal`)은 JSON 숫자로 직렬화된다(예: `459000.00`)
- 정산 배치는 자동(스케줄러) 또는 관리자 수동 실행

---

## 판매자 정산

셀러 정산 조회/지급 신청 API는 `user-service`로 이관됐다(`#233`).
`GET /api/v2/sellers/me/settlements`, `.../summary`, `PATCH .../payout-request` 등은
`docs/api-spec/user.md`를 참고한다.

---

## 관리자 정산 관리 · 지급 관리

관리자 정산 목록/요약 조회, 승인/보류/해제/취소, 지급/지급보류/지급보류해제 API는
`admin-service`로 이관됐다(`#234`). `GET /api/v2/admin/settlements`, `.../summary`,
`PATCH .../approve|hold|release-hold|cancel|payout|payout-hold|payout-hold/release` 등은
`docs/api-spec/admin.md`를 참고한다.

---

## 관리자 — 정산 배치

> 정산 배치(수동 실행)는 **비동기**다. POST 는 잡을 실행 접수만 하고 즉시 응답하며,
> 완료 여부는 상태 조회 API 를 폴링해서 확인한다. 잡이 완료(`COMPLETED`)되면 정산 목록
> (`GET /admin/settlements`, admin-service)을 다시 조회해 새로 생성된 정산 건을 표시한다.

### POST /admin/settlements/batch — 정산 배치 수동 실행(비동기)

- 인증: 필요
- 필요 역할: ADMIN
- triggerType: MANUAL (수동 실행은 비동기로 접수)
- 정산 대상 기간은 월 단위가 아니라 **월요일부터 일요일까지의 주간**이다.

#### Request

**Headers**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | Y | 게이트웨이가 주입하는 관리자 ID. 실행자(actorId)로 기록 |
| `X-User-Role` | Y | 게이트웨이가 주입하는 사용자 역할. `ADMIN` 필요 |

**Body**

```json
{
  "periodStart": "2026-07-13",
  "periodEnd": "2026-07-19"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `periodStart` | LocalDate | Y | 정산 포함 시작일. 반드시 월요일이어야 함 |
| `periodEnd` | LocalDate | Y | 정산 포함 종료일. 반드시 `periodStart` 로부터 6일 뒤(일요일)여야 함 |

> `periodStart`가 월요일이 아니거나 `periodEnd`가 `periodStart + 6일`(일요일)이 아니면 검증 오류(`400`)로 거부된다.

#### Response 202

비동기 실행을 접수했음을 의미한다. 응답은 잡 실행 식별자와 시작 시점 상태만 담는다.
정산 건수·금액 합계 같은 결과 정보는 이 응답에 없으며, 완료 후 정산 목록 조회로 얻는다.

```json
{
  "success": true,
  "data": {
    "jobExecutionId": 1024,
    "jobName": "settlementJob",
    "status": "STARTING",
    "startTime": null
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `jobExecutionId` | Long | 잡 실행 식별자. 상태 조회 API 의 path 로 사용 |
| `jobName` | String | 잡 이름. `settlementJob` 고정 |
| `status` | String | 접수 시점 실행 상태. 비동기라 보통 `STARTING` / `STARTED` |
| `startTime` | DateTime | 시작 시각. 접수 직후에는 아직 시작 전이라 `null` 일 수 있음 |

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `400` | S-003 | 요청 값 오류(`periodStart`/`periodEnd` 누락, 주간(월~일) 범위 아님 등) |
| `500` | S-002 | 정산 배치 잡 실행 실패 |

---

### GET /admin/settlements/batch/{jobExecutionId} — 정산 배치 잡 상태 조회

비동기로 실행한 배치 잡의 진행/완료 상태를 조회한다. 프론트는 이 API 를 폴링(예: 2~5초 간격)해
`status` 가 `COMPLETED`(또는 `FAILED`)가 되면 폴링을 멈추고 정산 목록을 재조회한다.

- 인증: 필요
- 필요 역할: ADMIN

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `jobExecutionId` | Long | 수동 실행 응답으로 받은 잡 실행 식별자 |

#### Request Example

```
GET /api/v2/admin/settlements/batch/1024
X-User-Id: 88aaaaaa-1111-2222-3333-444455556666
X-User-Role: ADMIN
```

#### Response 200

```json
{
  "success": true,
  "data": {
    "jobExecutionId": 1024,
    "jobName": "settlementJob",
    "status": "COMPLETED",
    "exitCode": "COMPLETED",
    "startTime": "2026-06-03T02:00:00",
    "endTime": "2026-06-03T02:00:12",
    "failureMessage": null
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `jobExecutionId` | Long | 잡 실행 식별자 |
| `jobName` | String | 잡 이름. `settlementJob` 고정 |
| `status` | String | 실행 상태. `STARTING` / `STARTED` / `COMPLETED` / `FAILED` / `STOPPED` 등 |
| `exitCode` | String | 종료 코드. `COMPLETED` / `FAILED` 등. 실행 중에는 `UNKNOWN` |
| `startTime` | DateTime | 시작 시각 |
| `endTime` | DateTime | 종료 시각. 실행 중이면 `null` |
| `failureMessage` | String | 실패 사유. 실패가 아니면 `null` |

#### 폴링 플로우

```
POST  /admin/settlements/batch        → 202 { jobExecutionId, status: STARTING }
GET   /admin/settlements/batch/{id}   → 200 { status: STARTED }      ┐ 반복
GET   /admin/settlements/batch/{id}   → 200 { status: STARTED }      ┘
GET   /admin/settlements/batch/{id}   → 200 { status: COMPLETED }    → 폴링 종료 → 정산 목록 재조회
```

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `404` | S-008 | 해당 `jobExecutionId` 의 잡 실행 이력이 없음 |

---

## 내부 API (Internal)

### 정산 배치 자동 실행 (Kubernetes CronJob)

- 호출 대상: Kubernetes `CronJob`이 트리거하는 `SettlementCronJobRunner`(`ApplicationRunner`)
- triggerType: SCHEDULED
- HTTP 엔드포인트가 아니다. CronJob 트리거 시점에 러너가 유스케이스를 직접 호출한다.
- 정산 대상 기간: 실행 시점 기준 직전 주(월요일~일요일, `SettlementPeriod.previousWeek(...)`)

실행 결과(성공/실패)는 별도 응답 없이 `settlement_batch` 레코드의 상태로 남는다.
실패 시 잡 리스너가 해당 배치를 `FAILED` 로 마감하고 사유를 기록한다.
