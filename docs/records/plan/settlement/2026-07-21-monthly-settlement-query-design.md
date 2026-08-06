# 월별 정산 동적 집계 조회 설계

- 작성일: 2026-07-21
- 관련 이슈: `#462 (이슈)`
- 작업 브랜치: `feat/#462-monthly-settlement-query`

## 1. 배경

정산 계산 결과는 월별 지급 객체가 아니라 월요일부터 일요일까지의 주간 정산 행으로 저장된다.
판매자와 어드민은 이 주간 행을 조회하고, 판매자는 승인된 주간 정산별로 지급을 신청하며,
어드민은 기존 상태 전이 API로 각 주간 정산을 승인·보류·지급·취소한다.

화면에서는 월 단위로 정산을 훑고 그 안의 주간 정산을 펼쳐 보려 한다. 이를 위해 별도의 월간
정산 엔티티나 지급 배치를 만들면 실제 지급 단위인 주간 정산과 중복 상태가 생긴다. 따라서
`seller_settlement`의 주간 행을 운영 데이터의 단일 진실 공급원으로 유지하고, 월별 목록과 상세는
조회 시 데이터베이스에서 동적으로 집계한다.

현재 `#462 (이슈)`에 적힌 월간 지급 객체·연결 테이블·월간 지급 Job 방향은 이 설계와 맞지 않는다.
설계 검토가 끝나면 이슈 본문을 월별 동적 집계 조회 범위로 전면 수정한다.

## 2. 목표와 제외 범위

### 목표

- 판매자와 어드민의 기존 정산 목록을 월별 집계 목록으로 변경한다.
- 월별 집계 안의 주간 정산 전체를 조회하는 상세 API를 판매자와 어드민에 각각 추가한다.
- 월별 합계, 상태별 건수, 주간 상태와 가능한 액션을 한 계약으로 제공한다.
- 월별 집계 중에도 주간 지급 신청과 어드민 상태 전이 흐름을 그대로 유지한다.
- 판매자와 어드민이 같은 `seller_settlement` 행을 기준으로 같은 월별 금액을 계산하게 한다.
- 어드민 목록과 상세에는 판매자명을 벌크 조회해 함께 제공한다.
- 어드민 요약 API는 선택한 월 기준 집계를 지원한다.

### 제외

- 월간 정산 엔티티, 월간 지급 엔티티, 주간-월간 연결 테이블 추가
- 월별 집계 결과를 미리 계산하거나 저장하는 배치·캐시·물리화 뷰
- 월 단위 자동 지급 또는 여러 주간 정산을 한 번에 지급하는 API
- 기존 주간 지급 신청·승인·보류·지급·취소 API의 경로나 전이 규칙 변경
- 판매자 누적 정산 요약 API의 의미 변경
- 프론트엔드 코드 구현
- 데이터베이스 마이그레이션과 운영 데이터 삭제

기존 데이터는 개발용 더미이므로 로컬·개발 환경에서 필요하면 초기화할 수 있다. 운영 데이터 삭제나
삭제 마이그레이션은 만들지 않는다.

## 3. 데이터와 도메인 정책

### 3.1 운영 데이터의 기준

`seller_settlement`의 한 행은 하나의 주간 정산이다. user-service의 `SellerSettlement`와
admin-service의 `Settlement`는 같은 테이블을 각 서비스 책임에 맞게 매핑한다. 월별 조회를 위해
새 JPA 엔티티를 만들지 않는다.

두 서비스는 내부 HTTP나 gRPC로 서로 호출하지 않는다. 각 서비스의 조회 어댑터가 같은
`seller_settlement` 테이블에서 월별 집계와 주간 상세를 직접 조회한다.

### 3.2 정산 월 결정

월요일부터 일요일까지 7일인 주간 정산은 7일 중 더 많은 날짜가 속한 월의 정산으로 분류한다.
7일의 가운데 날짜인 목요일이 어느 월인지 보면 항상 같은 결과를 얻는다.

```text
settlementMonth = YearMonth.from(periodStart.plusDays(3))
```

예시는 다음과 같다.

| 주간 정산 기간 | 가운데 날짜 | settlementMonth |
| --- | --- | --- |
| 2026-06-29 ~ 2026-07-05 | 2026-07-02 | 2026-07 |
| 2026-07-27 ~ 2026-08-02 | 2026-07-30 | 2026-07 |
| 2026-08-03 ~ 2026-08-09 | 2026-08-06 | 2026-08 |

기존 주간 정산 생성 규칙이 월요일 시작·일요일 종료를 보장한다. 이 규칙을 어긴 과거 데이터나 과거
월간 데이터에 대한 호환 분기는 만들지 않는다.

### 3.3 월별 합계와 취소 행

월별 상위 집계의 금액과 판매 건수에는 `CANCELLED`가 아닌 주간 정산만 포함한다.
취소 행은 지급 가능한 실적이 아니지만 이력 자체는 사라지면 안 되므로 상세와 상태 건수에는 포함한다.

| 필드 | 계산 기준 |
| --- | --- |
| `weeklySettlementCount` | 해당 월의 모든 주간 정산 수, 취소 포함 |
| `aggregatedSettlementCount` | 금액·판매 건수 합계에 포함된 주간 정산 수, 취소 제외 |
| `salesCount` | 취소가 아닌 행의 `product_count` 합계 |
| `grossAmount` | 취소가 아닌 행의 `total_amount` 합계 |
| `feeAmount` | 취소가 아닌 행의 `fee_total_amount` 합계 |
| `refundAmount` | 취소가 아닌 행의 `refund_amount` 합계, null은 0으로 처리 |
| `payoutAmount` | 취소가 아닌 행의 `settlement_total_amount` 합계 |
| `statusCounts` | 취소를 포함한 모든 주간 정산의 실제 상태별 건수 |

월별 상위 객체에는 단일 상태가 없다. 같은 달 안에 승인, 지급 신청, 지급 완료 등 여러 상태가 함께
존재할 수 있기 때문이다. `statusCounts`는 아래 7개 상태 중 건수가 1 이상인 상태만 enum 순서대로
반환한다.

1. `WAITING` / 대기
2. `APPROVAL_ON_HOLD` / 승인 보류
3. `APPROVED` / 승인
4. `PAYOUT_REQUESTED` / 지급 신청
5. `PAYOUT_ON_HOLD` / 지급 보류
6. `PAID` / 지급 완료
7. `CANCELLED` / 취소

## 4. API 계약

### 4.1 엔드포인트

| 대상 | 메서드와 경로 | 변경 내용 |
| --- | --- | --- |
| 판매자 목록 | `GET /api/v2/sellers/me/settlements` | 기존 주간 목록 응답을 월별 목록으로 변경 |
| 판매자 상세 | `GET /api/v2/sellers/me/settlements/months/{settlementMonth}` | 월 안의 주간 정산 상세 추가 |
| 어드민 목록 | `GET /api/v2/admin/settlements` | 기존 주간 목록 응답을 판매자별 월별 목록으로 변경 |
| 어드민 상세 | `GET /api/v2/admin/settlements/sellers/{sellerId}/months/{settlementMonth}` | 판매자·월 기준 주간 상세 추가 |
| 어드민 요약 | `GET /api/v2/admin/settlements/summary` | 선택적 `settlementMonth` 필터 추가 |

admin-service의 실제 컨트롤러 base path는 `${api.init}/admin/settlements`를 유지하며, 현재 운영
설정의 `${api.init}` 값인 `/api/v2`를 표에 표시했다. 정산 API만 별도로 버전을 낮추거나 공통
`api.init` 값을 변경하지 않는다.

기존 판매자와 어드민의 `PATCH` 엔드포인트는 모두 유지한다. 월별 상세의 `settlementId`를 사용해
기존 주간 상태 전이 API를 호출한다.

### 4.2 목록 요청

판매자와 어드민 목록은 다음 query parameter를 사용한다.

| 이름 | 형식 | 필수 | 기본값 | 의미 |
| --- | --- | --- | --- | --- |
| `settlementMonth` | `yyyy-MM` | 아니요 | 전체 월 | 특정 월만 조회 |
| `status` | 7개 상태 enum | 아니요 | 전체 상태 | 해당 상태 행이 하나 이상 있는 월별 그룹만 조회 |
| `page` | 0 이상의 정수 | 아니요 | `0` | 0-base 페이지 |
| `size` | 1~100 정수 | 아니요 | 판매자 `10`, 어드민 `20` | 월별 그룹 페이지 크기 |

`status`는 월별 합계 자체를 잘라내는 필터가 아니다. 예를 들어 2026-07에 `APPROVED` 행이 하나라도
있으면 `status=APPROVED` 조회 결과에 2026-07 그룹이 나온다. 이때 금액 합계와 `statusCounts`는
승인 행만이 아니라 그 달의 전체 주간 정산을 기준으로 계산한다. 상세 API도 목록의 상태 필터를
이어받지 않고 해당 월 전체를 반환한다.

`totalElements`는 주간 행 수가 아니라 월별 그룹 수다.

- 판매자: 본인의 월 수
- 어드민: 판매자와 월 조합 수

정렬은 고정한다.

- 판매자 목록: `settlementMonth DESC`
- 어드민 목록: `settlementMonth DESC`, 같은 월이면 `sellerId ASC`
- 월별 상세의 주간 정산: `periodStart ASC`

임의 정렬 query parameter는 제공하지 않는다.

월별 상위 객체는 저장된 엔티티가 아니므로 별도의 월별 ID를 만들지 않는다. 판매자는 인증된
`sellerId + settlementMonth`, 어드민은 경로의 `sellerId + settlementMonth`를 자연키로 사용한다.
응답 필드명은 `settlementMonth`로 통일한다. 기존 이슈 초안에 있던 `monthlyPayoutId`,
`payoutMonth`, `cutoffAt`, 월별 단일 `status`, 월별 `paidAt`은 반환하지 않는다. 지급 완료 시각은
실제로 지급된 주간 행의 `paidAt`에서 확인한다.

### 4.3 판매자 목록 응답 예시

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "settlementMonth": "2026-07",
        "weeklySettlementCount": 3,
        "aggregatedSettlementCount": 2,
        "salesCount": 22,
        "grossAmount": 2200000.00,
        "feeAmount": 330000.00,
        "refundAmount": 100000.00,
        "payoutAmount": 1770000.00,
        "statusCounts": [
          {
            "status": "APPROVED",
            "statusLabel": "승인",
            "count": 1
          },
          {
            "status": "PAID",
            "statusLabel": "지급 완료",
            "count": 1
          },
          {
            "status": "CANCELLED",
            "statusLabel": "취소",
            "count": 1
          }
        ]
      }
    ],
    "totalElements": 1,
    "page": 0,
    "size": 10
  },
  "message": "success"
}
```

판매자 응답에는 `sellerId`, `sellerName`을 넣지 않는다. 인증된 판매자 ID는 `X-User-Id`에서 받고
모든 조회 조건에 강제로 적용한다.

### 4.4 판매자 상세 응답 예시

요청 예시는 `GET /api/v2/sellers/me/settlements/months/2026-07`이다.

```json
{
  "success": true,
  "data": {
    "settlementMonth": "2026-07",
    "weeklySettlementCount": 3,
    "aggregatedSettlementCount": 2,
    "salesCount": 22,
    "grossAmount": 2200000.00,
    "feeAmount": 330000.00,
    "refundAmount": 100000.00,
    "payoutAmount": 1770000.00,
    "statusCounts": [
      {
        "status": "APPROVED",
        "statusLabel": "승인",
        "count": 1
      },
      {
        "status": "PAID",
        "statusLabel": "지급 완료",
        "count": 1
      },
      {
        "status": "CANCELLED",
        "statusLabel": "취소",
        "count": 1
      }
    ],
    "weeklySettlements": [
      {
        "settlementId": "11111111-1111-1111-1111-111111111111",
        "periodStart": "2026-06-29",
        "periodEnd": "2026-07-05",
        "salesCount": 10,
        "grossAmount": 1000000.00,
        "feeAmount": 150000.00,
        "refundAmount": 0.00,
        "payoutAmount": 850000.00,
        "status": "APPROVED",
        "statusLabel": "승인",
        "calculatedAt": "2026-07-06T00:05:00",
        "approvedAt": "2026-07-06T10:00:00",
        "payoutRequestedAt": null,
        "paidAt": null,
        "cancelledAt": null,
        "availableActions": [
          {
            "type": "REQUEST_PAYOUT",
            "label": "지급 신청하기"
          }
        ]
      },
      {
        "settlementId": "22222222-2222-2222-2222-222222222222",
        "periodStart": "2026-07-06",
        "periodEnd": "2026-07-12",
        "salesCount": 12,
        "grossAmount": 1200000.00,
        "feeAmount": 180000.00,
        "refundAmount": 100000.00,
        "payoutAmount": 920000.00,
        "status": "PAID",
        "statusLabel": "지급 완료",
        "calculatedAt": "2026-07-13T00:05:00",
        "approvedAt": "2026-07-13T10:00:00",
        "payoutRequestedAt": "2026-07-14T09:00:00",
        "paidAt": "2026-07-14T15:00:00",
        "cancelledAt": null,
        "availableActions": []
      },
      {
        "settlementId": "33333333-3333-3333-3333-333333333333",
        "periodStart": "2026-07-13",
        "periodEnd": "2026-07-19",
        "salesCount": 5,
        "grossAmount": 500000.00,
        "feeAmount": 75000.00,
        "refundAmount": 0.00,
        "payoutAmount": 425000.00,
        "status": "CANCELLED",
        "statusLabel": "취소",
        "calculatedAt": "2026-07-20T00:05:00",
        "approvedAt": null,
        "payoutRequestedAt": null,
        "paidAt": null,
        "cancelledAt": "2026-07-20T11:00:00",
        "availableActions": []
      }
    ]
  },
  "message": "success"
}
```

상세의 취소 행 금액은 이력 확인을 위해 원본 값 그대로 보여 준다. 다만 상위 합계에는 포함하지 않는다.
외부에는 기존 공개 식별자인 `settlementId`만 내리고 내부 PK인 `sellerSettlementId`는 노출하지 않는다.

### 4.5 어드민 목록 응답 예시

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "sellerId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "sellerName": "프롬프트 상점",
        "settlementMonth": "2026-07",
        "weeklySettlementCount": 3,
        "aggregatedSettlementCount": 2,
        "salesCount": 22,
        "grossAmount": 2200000.00,
        "feeAmount": 330000.00,
        "refundAmount": 100000.00,
        "payoutAmount": 1770000.00,
        "statusCounts": [
          {
            "status": "APPROVED",
            "statusLabel": "승인",
            "count": 1
          },
          {
            "status": "PAID",
            "statusLabel": "지급 완료",
            "count": 1
          },
          {
            "status": "CANCELLED",
            "statusLabel": "취소",
            "count": 1
          }
        ]
      }
    ],
    "totalElements": 1,
    "page": 0,
    "size": 20
  },
  "message": "success"
}
```

### 4.6 어드민 상세 응답 예시

요청 예시는
`GET /api/v2/admin/settlements/sellers/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/months/2026-07`이다.

```json
{
  "success": true,
  "data": {
    "sellerId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    "sellerName": "프롬프트 상점",
    "settlementMonth": "2026-07",
    "weeklySettlementCount": 3,
    "aggregatedSettlementCount": 2,
    "salesCount": 22,
    "grossAmount": 2200000.00,
    "feeAmount": 330000.00,
    "refundAmount": 100000.00,
    "payoutAmount": 1770000.00,
    "statusCounts": [
      {
        "status": "APPROVED",
        "statusLabel": "승인",
        "count": 1
      },
      {
        "status": "PAID",
        "statusLabel": "지급 완료",
        "count": 1
      },
      {
        "status": "CANCELLED",
        "statusLabel": "취소",
        "count": 1
      }
    ],
    "weeklySettlements": [
      {
        "settlementId": "11111111-1111-1111-1111-111111111111",
        "periodStart": "2026-06-29",
        "periodEnd": "2026-07-05",
        "salesCount": 10,
        "grossAmount": 1000000.00,
        "feeAmount": 150000.00,
        "refundAmount": 0.00,
        "payoutAmount": 850000.00,
        "status": "APPROVED",
        "statusLabel": "승인",
        "calculatedAt": "2026-07-06T00:05:00",
        "approvedAt": "2026-07-06T10:00:00",
        "payoutRequestedAt": null,
        "paidAt": null,
        "cancelledAt": null,
        "availableActions": [
          {
            "type": "CANCEL",
            "label": "정산 취소"
          }
        ]
      },
      {
        "settlementId": "22222222-2222-2222-2222-222222222222",
        "periodStart": "2026-07-06",
        "periodEnd": "2026-07-12",
        "salesCount": 12,
        "grossAmount": 1200000.00,
        "feeAmount": 180000.00,
        "refundAmount": 100000.00,
        "payoutAmount": 920000.00,
        "status": "PAID",
        "statusLabel": "지급 완료",
        "calculatedAt": "2026-07-13T00:05:00",
        "approvedAt": "2026-07-13T10:00:00",
        "payoutRequestedAt": "2026-07-14T09:00:00",
        "paidAt": "2026-07-14T15:00:00",
        "cancelledAt": null,
        "availableActions": []
      },
      {
        "settlementId": "33333333-3333-3333-3333-333333333333",
        "periodStart": "2026-07-13",
        "periodEnd": "2026-07-19",
        "salesCount": 5,
        "grossAmount": 500000.00,
        "feeAmount": 75000.00,
        "refundAmount": 0.00,
        "payoutAmount": 425000.00,
        "status": "CANCELLED",
        "statusLabel": "취소",
        "calculatedAt": "2026-07-20T00:05:00",
        "approvedAt": null,
        "payoutRequestedAt": null,
        "paidAt": null,
        "cancelledAt": "2026-07-20T11:00:00",
        "availableActions": []
      }
    ]
  },
  "message": "success"
}
```

판매자명을 찾지 못해도 월별 정산은 누락시키거나 실패시키지 않는다. 해당 항목의 `sellerName`만
`null`로 반환하고 `sellerId`는 유지하며 warn 로그를 남긴다. 판매자명은 표시용이며 검색·정렬
조건에는 사용하지 않는다.

### 4.7 주간 정산 액션

판매자 상세는 승인된 본인 정산에만 다음 액션을 제공한다.

| 현재 상태 | 판매자 `availableActions` |
| --- | --- |
| `APPROVED` | `REQUEST_PAYOUT` / 지급 신청하기 |
| 그 외 | 빈 배열 |

어드민 상세는 현재 도메인 전이 규칙으로 실행 가능한 액션만 제공한다.

| 현재 상태 | 어드민 `availableActions` |
| --- | --- |
| `WAITING` | `APPROVE` / 승인, `HOLD` / 승인 보류, `CANCEL` / 정산 취소 |
| `APPROVAL_ON_HOLD` | `RELEASE_HOLD` / 승인 보류 해제, `CANCEL` / 정산 취소 |
| `APPROVED` | `CANCEL` / 정산 취소 |
| `PAYOUT_REQUESTED` | `PAYOUT` / 지급 완료, `PAYOUT_HOLD` / 지급 보류, `CANCEL` / 정산 취소 |
| `PAYOUT_ON_HOLD` | `RELEASE_PAYOUT_HOLD` / 지급 보류 해제, `CANCEL` / 정산 취소 |
| `PAID` | 빈 배열 |
| `CANCELLED` | 빈 배열 |

액션 객체는 `type`, `label`만 가진다. URL과 HTTP 메서드는 기존 API 계약을 프론트엔드가 매핑한다.

### 4.8 빈 결과와 오류

- 목록에 조건을 만족하는 그룹이 없으면 200과 빈 `items`를 반환한다.
- 판매자 상세에서 본인에게 해당 월의 정산이 없으면 404를 반환한다.
- 어드민 상세에서 `sellerId`와 `settlementMonth` 조합이 없으면 404를 반환한다.
- `settlementMonth` 형식 오류, 정의되지 않은 `status`, 음수 `page`, 범위를 벗어난 `size`는 400을 반환한다.
- 판매자 조회는 항상 `X-User-Id`를 조건에 포함해 다른 판매자 데이터를 노출하지 않는다.
- 기존 인증·인가 오류와 상태 전이 충돌 응답은 유지한다.

성공 응답은 기존 `ApiResult<T>`의 `success`, `data`, `message` 구조를 유지한다. 오류는 공통
`ErrorResponse`를 사용하고, Controller와 Swagger에 200·400·401·403·404 응답을 실제 계약에
맞게 기록한다.

## 5. 애플리케이션 구조

### 5.1 조회 흐름

판매자와 어드민은 각 모듈 안에 월별 조회용 use case, 조회 포트, 조회 어댑터와 응답 DTO를 둔다.
JPA 엔티티를 월별 객체처럼 재사용하지 않고, 조회 결과를 표현하는 불변 projection/record를 포트
반환 타입으로 사용한다.

```text
SellerSettlementController
  -> SellerSettlementUseCase
  -> SellerSettlementQueryRepository
  -> seller_settlement 직접 집계

SettlementController
  -> SettlementUseCase
  -> SettlementQueryRepository
  -> seller_settlement 직접 집계
  -> SellerNameQueryPort
  -> admin.user UserRepository 벌크 조회
```

목록 한 페이지는 다음 순서로 조립한다.

1. 월별 그룹과 합계, 페이지의 `totalElements`를 조회한다.
2. 현재 페이지에 포함된 그룹들의 상태별 건수를 한 번에 조회한다.
3. 어드민은 현재 페이지의 판매자 ID를 모아 판매자명을 한 번에 조회한다.
4. 애플리케이션 서비스가 그룹, 상태 건수, 판매자명을 응답 DTO로 조립한다.

상세는 한 판매자·한 월의 월별 합계 조회와 그 달의 주간 행 조회를 조합한다. 목록과 상세가 같은
월 결정식과 취소 제외 합계 규칙을 공유하게 조회 포트의 공통 값 타입 또는 집계 매퍼로 묶는다.

### 5.2 어드민 판매자명 포트

admin-service에는 `admin.settlement.application.port.SellerNameQueryPort`를 둔다. 구현 어댑터는
`admin.settlement` 하위에 두고 기존
`com.prompthub.admin.user.domain.repository.UserRepository.findAllByIds`에 위임한다.

어드민 정산 애플리케이션 서비스는 `admin.user` 패키지를 직접 참조하지 않는다. 어댑터만 기존 사용자
저장소와 사용자 모델을 알아야 한다. 한 페이지의 고유 판매자 ID를 한 번에 넘겨 N+1 조회를 막는다.

### 5.3 주간 상태 전이 유지

월별 조회는 읽기 모델만 바꾼다. `SellerSettlement`와 admin-service `Settlement`의 상태 전이 메서드,
기존 `PATCH` Controller, 취소 시 소스 라인 해제 로직은 변경하지 않는다.

상태 전이가 성공하면 프론트엔드는 현재 월의 목록과 상세 캐시를 무효화해 다시 조회한다. 백엔드는
월별 캐시를 저장하지 않으므로 다음 조회에서 변경된 주간 상태와 합계를 바로 계산한다.

## 6. 영속성 조회 설계

### 6.1 이식 가능한 월 계산식

테스트는 현재 H2를 사용하고 운영은 PostgreSQL을 사용한다. Testcontainers나 build 설정을 추가하지
않고 두 데이터베이스가 공통으로 지원하는 식만 사용한다.

```sql
EXTRACT(YEAR FROM (period_start + 3))
EXTRACT(MONTH FROM (period_start + 3))
```

`period_start + 3`은 주간 시작일에서 3일 뒤인 목요일이다. PostgreSQL과 현재 H2 2.4.240에서 위
날짜 덧셈과 `EXTRACT` 조합을 사용할 수 있다. 월 그룹 키는 연도와 월을 함께 사용해 서로 다른 연도의
같은 월이 합쳐지지 않게 한다.

특정 `settlementMonth` 조회는 계산식을 모든 행에 적용하는 대신 `period_start` 범위로 좁힐 수 있다.

```text
lowerInclusive = settlementMonth.atDay(1).minusDays(3)
upperExclusive = settlementMonth.plusMonths(1).atDay(1).minusDays(3)
```

예를 들어 2026-07은 `period_start >= 2026-06-28 AND period_start < 2026-07-29`다. 유효한
월요일 시작 주간 중 2026-06-29와 2026-07-27을 모두 포함한다.

### 6.2 합계와 필터

취소 제외 합계는 `CASE`를 사용한다.

```sql
SUM(CASE WHEN status <> 'CANCELLED' THEN total_amount ELSE 0 END)
```

판매 건수, 수수료, 환불, 지급액과 `aggregatedSettlementCount`에도 같은 조건을 적용한다.
`weeklySettlementCount`는 전체 `COUNT(*)`를 사용한다. 환불액은 `COALESCE(refund_amount, 0)`로
합산한다.

상태 필터는 집계 입력 행을 `WHERE status = :status`로 잘라내지 않는다. 해당 판매자·월 그룹에
필터 상태가 존재하는지 `EXISTS` 또는 같은 의미의 조건으로 제한하고, 합계 대상은 그 달 전체 행으로
유지한다.

### 6.3 페이지 쿼리

목록은 하나의 복잡한 CTE나 DB별 JSON 집계로 모든 응답을 만들지 않는다. 다음처럼 역할을 나눈다.

1. 월별 그룹 페이지와 취소 제외 합계
2. 월별 그룹 수를 세는 count query
3. 현재 페이지 그룹에 대한 상태별 count query
4. 어드민만 판매자명 벌크 query

이 구조는 반환 행 수를 월별 그룹 수에 맞춰 제어하고, 상태 배열과 사용자 모델 결합을 애플리케이션
경계에서 명시적으로 처리한다. 현재 데이터 규모에서는 조회마다 몇 개의 주간 행을 더하는 비용을
받아들이며, 저장된 집계와 원본 주간 데이터 사이의 동기화 문제를 만들지 않는다.

## 7. 화면 연동 기준

백엔드는 판매자와 어드민 모두 목록·상세 API를 분리한다. 상세 데이터가 필요할 때만 조회하는 흐름을
기준으로 한다.

### 판매자

- 월별 목록을 먼저 조회한다.
- 월 행을 누르면 노션 토글처럼 같은 행 아래에 주간 상세를 펼친다.
- 처음 펼칠 때만 해당 월 상세 API를 호출하고 이후에는 화면 캐시를 사용한다.
- 지급 신청 성공 후 해당 월 목록과 상세를 무효화하고 다시 조회한다.

### 어드민

- 월별 목록을 먼저 조회한다.
- 행을 선택하면 오른쪽 상세 패널에서 주간 정산을 보여 준다.
- 처음 선택할 때 해당 판매자·월 상세 API를 호출한다.
- 주간 상태 전이 성공 후 해당 월 목록, 상세와 월 필터가 적용된 요약을 무효화하고 다시 조회한다.

백엔드 응답은 특정 아코디언이나 패널 컴포넌트에 종속되지 않는다. 목록의 자연키인 판매자 ID와
정산 월로 상세를 요청하고, 주간 행의 `settlementId`로 기존 액션 API를 호출한다.

## 8. 어드민 요약

기존 `GET /api/v2/admin/settlements/summary`에 선택적 `settlementMonth=yyyy-MM`을 추가한다.
미지정하면 현재처럼 전체 기간을 집계한다. 지정하면 목요일 기준 월 결정 규칙으로 해당 월의 주간
행만 집계한다.

요약 카드의 기존 상태 버킷 규칙은 유지한다.

- `WAITING`, `APPROVAL_ON_HOLD` -> `WAITING`
- `APPROVED`, `PAYOUT_REQUESTED` -> `APPROVED`
- `PAYOUT_ON_HOLD` -> `PAYOUT_ON_HOLD`
- `PAID` -> `PAID`
- `CANCELLED` -> 카드 제외

월별 목록의 `statusCounts`는 7개 실제 상태를 보여 주므로 요약 카드의 버킷과 섞지 않는다.

## 9. 테스트 전략

### 월 분류와 집계

- 2026-06-29~2026-07-05가 2026-07로 분류되는지 검증
- 2026-07-27~2026-08-02가 2026-07로 분류되는지 검증
- 연도 경계 주간의 월 분류 검증
- 판매자와 어드민이 같은 행으로 같은 월별 금액·건수를 반환하는지 검증
- 취소 행이 상세·`weeklySettlementCount`·`statusCounts`에는 포함되고 금액·판매 건수와
  `aggregatedSettlementCount`에서는 제외되는지 검증
- null 환불액이 0으로 집계되는지 검증

### 필터와 페이지

- 상태가 하나라도 있는 그룹만 선택하면서 합계와 상태 건수는 전체 월 기준인지 검증
- 월 필터의 포함 하한·제외 상한 검증
- 판매자 월 수와 어드민 판매자-월 조합 수가 `totalElements`에 들어가는지 검증
- 판매자와 어드민의 고정 정렬, 페이지 경계, 빈 페이지 검증
- `page < 0`, `size < 1`, `size > 100`, 잘못된 월·상태가 400인지 검증

### 상세와 권한

- 상세가 해당 월의 주간 정산 전체를 `periodStart ASC`로 반환하는지 검증
- 없는 판매자·월 조합이 404인지 검증
- 판매자가 다른 판매자의 월별 집계와 상세를 조회할 수 없는지 검증
- 상태별 판매자·어드민 `availableActions`와 nullable 생명주기 시각 검증
- 외부 응답에 `sellerSettlementId`가 노출되지 않는지 검증

### 판매자명과 요약

- 어드민 한 페이지의 판매자명을 한 번의 벌크 포트 호출로 조회하는지 검증
- 판매자 정보가 없는 경우 `sellerName: null`로 응답하고 정산 그룹은 유지하는지 검증
- 어드민 요약의 월 필터와 기존 전체 기간·카드 버킷 동작 검증

### 회귀와 문서

- 기존 판매자 주간 지급 신청과 어드민 상태 전이·취소 테스트 유지
- 판매자 누적 요약 결과가 바뀌지 않는지 검증
- 판매자와 어드민 Controller 계약, JSON 필드와 Swagger 응답 문서 검증
- user-service와 admin-service 전체 테스트 실행
- `git diff --check` 실행

## 10. 대안과 선택 근거

### 월간 엔티티와 연결 테이블

월별 식별자와 지급 상태를 저장하기 편하지만 주간 지급 상태와 월간 지급 상태가 중복된다. 이번 요구는
월별 표시와 합계 조회이며 실제 지급은 계속 주간 단위이므로 사용하지 않는다.

### 애플리케이션 전체 행 조회 후 그룹화

구현은 단순하지만 전체 주간 행을 메모리에 적재해야 하고 정확한 그룹 페이지 처리가 어렵다. DB가
그룹과 합계를 계산하고 애플리케이션은 한 페이지 결과만 조립한다.

### 단일 CTE와 JSON 집계

한 번의 왕복으로 응답을 만들 수 있지만 쿼리 복잡도와 H2/PostgreSQL 호환 비용이 커진다. 그룹 페이지,
상태 건수, 판매자명 조회를 분리해 각 책임과 테스트 경계를 명확히 한다.

### 미리 계산한 월별 집계

조회는 빨라지지만 주간 상태 전이마다 집계 동기화와 캐시 무효화가 필요하다. 한 달의 주간 정산 수가
작고 조회 시 합계 비용이 제한적이므로 원본 행을 동적으로 집계한다.

## 11. 완료 조건

- 판매자와 어드민 목록이 합의된 월 결정·취소 제외·상태 필터 규칙으로 월별 그룹을 반환한다.
- 판매자와 어드민 상세가 해당 월의 주간 정산, 상태, 시각, 역할별 액션을 반환한다.
- 어드민 응답이 판매자명을 벌크 조회하고 누락된 이름을 안전하게 처리한다.
- 어드민 요약이 선택한 월과 전체 기간을 모두 지원한다.
- 기존 주간 지급·상태 전이와 판매자 누적 요약이 유지된다.
- 새 엔티티·테이블·월간 배치·마이그레이션 없이 구현된다.
- H2와 PostgreSQL 공통 SQL 범위에서 조회가 동작한다.
- Controller와 Swagger가 변경된 목록과 신규 상세 계약을 문서화한다.
- 관련 단위·영속성·Controller·회귀 테스트와 전체 모듈 테스트가 통과한다.
- 검토가 끝난 설계를 기준으로 `#462 (이슈)` 제목과 본문을 수정한다.
