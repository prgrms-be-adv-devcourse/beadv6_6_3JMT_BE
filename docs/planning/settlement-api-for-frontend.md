# 정산 수동 배치 API 로컬 검증 가이드

settlement-service의 배치 실행을 로컬 Swagger 또는 HTTP 요청으로 확인할 때 사용하는 가이드다.
이 API는 운영 프론트 연동용이 아니며 `settlement.manual-api.enabled=true`인 환경에서만 열린다.

> 일반 정산 관리 API(목록·요약·승인·보류·지급·취소)는 admin-service가 제공한다.
> settlement-service에는 배치 테스트용 `SettlementBatchController`만 조건부로 남는다.

## 0. 기본 정보

| 항목 | 값 |
| --- | --- |
| settlement-service 직접 호출 | `http://localhost:8085` |
| 운영 Gateway 경유 호출 | 지원하지 않음(라우트 미노출) |
| API prefix | `/api/v2` |
| 배치 API | `/api/v2/admin/settlements/batch` |
| OpenAPI JSON 직접 조회 | `http://localhost:8085/v3/api-docs` |
| 활성화 조건 | `settlement.manual-api.enabled=true` |

`application-local.yml`은 수동 API를 활성화한다. 다른 프로필에서 확인해야 한다면 실행 시
`settlement.manual-api.enabled=true`를 명시한다. 공통 기본값은 `false`다.

### 요청 헤더

| 엔드포인트 | 헤더 | 용도 |
| --- | --- | --- |
| POST 수동 실행 | `X-User-Id: UUID` | 수동 실행자 `actorId` 기록 |
| GET 상태 조회 | 없음 | Job Execution ID로 상태 조회 |

- settlement-service는 역할을 검사하지 않는다.
- 운영 Gateway는 settlement-service 라우트를 제공하지 않으며, 이 수동 API 자체도 운영 기본 설정에서
  비활성이다. 로컬에서 settlement-service를 직접 실행해 검증할 때만 사용한다.

## 1. 엔드포인트

| 메서드 | 경로 | 설명 | 응답 |
| --- | --- | --- | --- |
| POST | `/api/v2/admin/settlements/batch` | 정산 배치잡 비동기 실행 접수 | `202` + `SettlementJobResponse` |
| GET | `/api/v2/admin/settlements/batch/{jobExecutionId}` | 배치잡 상태 조회 | `200` + `SettlementJobStatusResponse` |

배치 실행은 비동기다. POST 응답에서 `jobExecutionId`를 받고, GET으로 조회해 `status`가
`COMPLETED` 또는 `FAILED`가 될 때까지 확인한다.

### 1-1. 배치잡 실행

```http
POST /api/v2/admin/settlements/batch
X-User-Id: 00000000-0000-0000-0000-000000000394
Content-Type: application/json

{
  "periodStart": "2026-07-13",
  "periodEnd": "2026-07-19"
}
```

요청 바디:

```ts
interface RunSettlementJobRequest {
  periodStart: string; // 포함 시작일, 월요일 yyyy-MM-dd
  periodEnd: string;   // 포함 종료일, 일요일 yyyy-MM-dd
}
```

응답: `202 Accepted`

```ts
interface SettlementJobResponse {
  jobExecutionId: number;
  jobName: string;
  status: string;
  startTime: string;
}
```

### 1-2. 배치잡 상태 조회

```http
GET /api/v2/admin/settlements/batch/1024
```

```ts
interface SettlementJobStatusResponse {
  jobExecutionId: number;
  jobName: string;
  status: string;
  exitCode: string;
  startTime: string;
  endTime: string | null;
  failureMessage: string | null;
}
```

## 2. 에러 응답

비즈니스 예외는 공통 `ErrorResponse` 형식으로 내려온다.

```ts
interface ErrorResponse {
  code: string;
  message: string;
}
```

### 에러 코드표

| Code | HTTP | 메시지 |
| --- | --- | --- |
| S-001 | 404 | 정산 배치를 찾을 수 없습니다. |
| S-002 | 500 | 정산 배치 잡 실행에 실패했습니다. |
| S-003 | 400 | 요청 값이 올바르지 않습니다. |
| S-004 | 500 | 예상하지 못한 서버 오류가 발생했습니다. |
| S-007 | 409 | 정산 배치가 처리 중 상태가 아닙니다. |
| S-008 | 404 | 정산 배치 잡 실행 이력을 찾을 수 없습니다. |
| S-017 | 500 | 정산 대상 라인 조회에 실패했습니다. |

### 엔드포인트별 주요 에러

| 상황 | HTTP | 비고 |
| --- | --- | --- |
| 필수 `X-User-Id` 누락 또는 UUID 형식 오류 | 400 | S-003 |
| `periodStart` 또는 `periodEnd` 누락 | 400 | S-003 |
| 시작일 또는 종료일 날짜 형식 오류 | 400 | S-003 |
| 월요일 시작·일요일 종료(7일) 규칙 위반 | 400 | S-003 |
| 잡 실행 이력 없음 | 404 | S-008 |
| 배치 상태 전이 불가 | 409 | S-007 |
| 잡 실행 또는 원천 라인 조회 실패 | 500 | S-002 / S-017 |

## 3. 로컬 검증 체크리스트

- [ ] `local` 프로필 또는 `settlement.manual-api.enabled=true`로 서비스 실행
- [ ] POST 요청에 유효한 UUID 형식의 `X-User-Id` 입력
- [ ] `periodStart`는 월요일, `periodEnd`는 그 주 일요일로 입력
- [ ] POST 202 응답의 `jobExecutionId` 저장
- [ ] GET 상태 조회를 반복해 `COMPLETED` 또는 `FAILED` 확인
- [ ] 운영 프론트 정산 기능은 admin-service API를 사용하고 이 API에 연결하지 않음
