# 어드민 홈 통합 조회 API 설계

## 목적

어드민 홈은 현재 프론트엔드에서 회원 통계, 월간 거래액, 최근 7일 거래량, 정산 요약, 검수 대기 상품을 각각 호출해 조합한다. 홈 화면의 로딩과 오류 처리가 여러 API 계약에 분산되어 있고, 검수 대기 상품은 미리보기 네 건만 필요하지만 전체 목록을 조회한다.

`admin-service`에 홈 화면 전용 읽기 모델과 `GET /api/v2/admin/home`을 추가해 홈 초기 데이터 전체를 한 번에 반환한다. 기존 회원·주문·정산·상품 API는 이번 작업에서 삭제하거나 계약을 변경하지 않는다.

## 결정 사항

1. 통합 API 경로는 `GET /api/v2/admin/home`이다.
2. 응답은 KPI와 최근 7일 거래 차트뿐 아니라 검수 대기 상품 전체 건수와 오래된 순 미리보기 네 건을 포함한다.
3. `com.prompthub.admin.home` 패키지가 화면 전용 읽기 모델을 소유한다.
4. `admin-service`가 하나의 데이터소스로 서비스별 PostgreSQL 스키마를 직접 조회한다. 다른 서비스에 REST, Feign, gRPC 요청을 보내지 않는다.
5. 날짜 경계는 서버 기본 시간대가 아니라 `Asia/Seoul`로 명시한다.
6. 정산 승인 대기는 `WAITING`과 `APPROVAL_ON_HOLD`를 합산한다.
7. 기존 `/admin/stats/users`, `/admin/orders/month`, `/admin/orders/weekend` 구현은 유지한다. 프론트 홈만 새 API로 전환하며, 기존 API 제거는 담당 팀의 후속 작업이다.
8. 다른 서비스가 소유한 스키마의 인덱스 마이그레이션은 이번 범위에 포함하지 않는다.

## 데이터 소유권과 접근 경로

`admin-service` 중앙 설정은 다음 스키마를 검색 경로에 포함하고 운영 DB 계정으로 직접 연결한다.

```text
currentSchema=user_service,product_service,order_service,payment_service,settlement_service
```

홈 API가 실제로 사용하는 원본은 다음 세 스키마다.

| 홈 데이터 | 실제 원본 |
| --- | --- |
| 전체 회원 수, 오늘 신규 회원 수 | `user_service."user"` |
| 이번 달 거래액, 최근 7일 거래 | `order_service."order"`, `order_service.order_product` |
| 정산 승인 대기 금액과 건수 | `user_service.seller_settlement` |
| 검수 대기 상품 | `product_service.product` |
| 검수 상품 판매자명 | `user_service."user"` |

정산 운영 상태의 단일 원본은 `settlement_service` 스키마가 아니라 `user_service.seller_settlement`다. `admin-service`의 기존 정산 기능도 이 테이블을 재매핑해 조회하고 상태를 변경한다.

모든 조회는 `admin-service` 프로세스 안에서 수행한다. 다른 서비스 스키마를 읽을 권한이 있다는 것은 조회 모델을 구성할 수 있다는 뜻이며, 해당 스키마의 DDL과 마이그레이션 소유권까지 `admin-service`로 이동한다는 뜻은 아니다.

## API 계약

### 요청

```http
GET /api/v2/admin/home
```

기존 어드민 API와 같은 Gateway 인증 및 ADMIN 권한 정책을 적용한다. 별도 요청 파라미터는 없다.

### 응답

```json
{
  "success": true,
  "data": {
    "generatedAt": "2026-07-22T15:30:00+09:00",
    "users": {
      "totalUsers": 1250,
      "todayNewUsers": 18
    },
    "transactions": {
      "monthlyTransactionAmount": 32500000,
      "recent7Days": {
        "totalTransactionCount": 142,
        "totalTransactionAmount": 8900000,
        "period": {
          "startDate": "2026-07-16",
          "endDate": "2026-07-22"
        },
        "dailyTransactions": [
          {
            "date": "2026-07-22",
            "transactionCount": 21,
            "transactionAmount": 1250000
          }
        ]
      }
    },
    "settlements": {
      "pendingApprovalAmount": 4200000,
      "pendingApprovalCount": 12
    },
    "pendingProducts": {
      "totalCount": 7,
      "items": [
        {
          "productId": "0b65e25d-eaa2-4ed5-b6dc-9f9fe0def0cc",
          "title": "상품명",
          "sellerNickname": "판매자",
          "productType": "IMAGE",
          "model": "GPT-5",
          "amount": 10000,
          "status": "PENDING_REVIEW",
          "createdAt": "2026-07-21T10:20:30"
        }
      ]
    }
  },
  "message": "success"
}
```

`generatedAt`은 요청 시작 시점의 KST 기준 시각이다. 금액 타입은 기존 API와 같은 JSON number 계약을 유지한다. 정산 금액은 내부적으로 `BigDecimal`을 사용하고 주문 금액은 `long` 범위로 계산한다.

## 통계 정의

### 회원

- `totalUsers`: 상태와 역할에 관계없는 전체 회원 행 수
- `todayNewUsers`: KST 오늘 00:00 이상, 다음 날 00:00 미만에 생성된 회원 수

### 거래

- `monthlyTransactionAmount`: KST 이번 달 시작 이상, 다음 달 시작 미만의 완료 주문 금액에서 같은 기간 환불 상품 금액을 차감
- `recent7Days`: 오늘을 포함한 연속된 7개 KST 날짜
- 일별 `transactionCount`: 해당 날짜에 완료된 주문 수
- 일별 `transactionAmount`: 해당 날짜 완료 주문 금액에서 해당 날짜 환불 상품 금액을 차감
- 거래가 없는 날짜도 `transactionCount=0`, `transactionAmount=0`으로 포함
- 월간·주간 집계 의미는 기존 주문 통계 API와 동일하게 유지

### 정산

- `pendingApprovalAmount`: `WAITING`, `APPROVAL_ON_HOLD` 행의 `settlement_total_amount` 합계
- `pendingApprovalCount`: 같은 상태의 정산 행 수
- 지급 신청이나 지급 보류 상태는 승인 대기 통계에 포함하지 않음

### 상품

- `totalCount`: `PENDING_REVIEW`이며 삭제되지 않은 전체 상품 수
- `items`: 같은 조건의 상품을 `created_at ASC`, 동률이면 `id ASC`로 정렬한 첫 네 건
- 판매자명은 `user_service."user"`와 연결해 조회
- 판매자 행이 없으면 기존 어드민 상품 정책과 동일하게 `알 수 없음` 반환

## 패키지와 컴포넌트

```text
com.prompthub.admin.home
├── presentation
│   ├── controller/HomeController
│   └── dto/response/HomeResponse
├── application
│   ├── dto/HomeResult
│   ├── usecase/HomeUseCase
│   └── service/HomeApplicationService
├── domain
│   └── repository/HomeQueryRepository
└── infrastructure
    └── persistence
        └── HomeQueryRepositoryAdapter
```

### `HomeController`

- `${api.init}/admin/home`의 GET 요청을 받는다.
- `HomeUseCase`가 반환한 `HomeResult`를 `ApiResult<HomeResponse>`로 변환한다.
- 기간 계산이나 DB 접근 로직을 갖지 않는다.

### `HomeApplicationService`

- 요청 시작 시 주입된 `Clock`으로 기준 시각을 한 번 생성한다.
- `Asia/Seoul` 기준으로 오늘, 이번 달, 최근 7일의 반개구간을 계산한다.
- `HomeQueryRepository`의 화면 전용 프로젝션을 조합한다.
- `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`를 사용해 모든 결과를 같은 PostgreSQL 스냅샷에서 읽는다.
- 하나의 트랜잭션과 커넥션을 유지하기 위해 쿼리를 별도 스레드로 병렬 실행하지 않는다.

### `HomeQueryRepository`

홈 화면에 필요한 최소 읽기 연산만 제공한다.

```text
findUserSummary(todayStart, tomorrowStart)
findMonthlyTransactionAmount(monthStart, nextMonthStart)
findDailyTransactions(sevenDaysStart, tomorrowStart)
findPendingApprovalSettlementSummary()
findPendingProductPreview(limit)
```

반환값은 기존 user/order/settlement/product presentation DTO가 아니라 `home` 전용 불변 프로젝션이다. 이를 통해 기존 API 계약 변경이 홈 API에 전파되거나 홈 응답이 기존 화면 DTO에 결합되는 것을 막는다.

### 조회 구현

`HomeQueryRepositoryAdapter`는 `NamedParameterJdbcTemplate`과 스키마를 명시한 SQL로 화면 전용 집계 쿼리를 실행한다. 검색 경로의 첫 스키마가 `user_service`이므로 주문과 상품 테이블을 unqualified name으로 조회하지 않는다. 모든 SQL은 `user_service`, `order_service`, `product_service` 스키마를 명시한다.

API 하나를 만들기 위해 모든 데이터를 하나의 대형 SQL이나 JSON 집계 쿼리로 합치지 않는다. 여러 스키마를 강하게 결합한 단일 SQL보다 목적이 분리된 작은 쿼리가 테스트와 변경에 유리하다. JPA 엔티티도 새로 복제하지 않고 읽기 결과를 홈 전용 불변 프로젝션에 매핑한다.

검수 상품은 전체 목록을 메모리에 올린 뒤 네 건을 자르지 않고 DB에서 count와 limit 조회를 각각 수행한다. 판매자명은 교차 스키마 left join 또는 미리보기 판매자 ID에 대한 제한된 후속 조회로 가져올 수 있으며, 구현 시 실행 계획이 단순한 쪽을 선택한다.

## 시간대 처리

`LocalDate.now()`나 JVM 기본 시간대를 직접 사용하지 않는다. 애플리케이션에 `Clock`과 `ZoneId.of("Asia/Seoul")`를 주입하고 다음 값을 한 기준 시각에서 계산한다.

- 오늘 시작과 다음 날 시작
- 이번 달 시작과 다음 달 시작
- 최근 7일 시작과 다음 날 시작

DB 컬럼은 현재 `timestamp without time zone`이므로 계산된 KST 로컬 경계를 `LocalDateTime`으로 전달한다. 운영 서버가 UTC로 실행되더라도 홈 통계의 날짜는 바뀌지 않는다.

## 오류 처리

- Gateway 인증 정보가 없으면 `401`, ADMIN 권한이 없으면 `403`을 유지한다.
- 집계 쿼리 하나라도 실패하면 기존 전역 예외 처리 경로를 통해 요청 전체를 실패시킨다.
- 조회 오류를 `0`이나 빈 목록으로 바꾸지 않는다. 실제 통계 0과 장애를 구분해야 한다.
- 판매자명 누락은 상품 자체의 조회 실패가 아니므로 `알 수 없음`으로 대체한다.
- 이 기능만을 위한 신규 비즈니스 오류 코드는 추가하지 않는다.

프론트는 단일 로딩 상태와 명시적인 오류·재시도 상태를 제공한다. 오류 응답을 정상 통계 0으로 렌더링하지 않는다.

## 성능과 인덱스

주문 스키마에는 기존 `order.completed_at`, `order_product.refunded_at` 인덱스가 있어 기간 집계를 지원한다. 현재 다음 홈 조회 조건에는 전용 인덱스가 없다.

- `user_service."user".created_at`
- `user_service.seller_settlement.status`
- `product_service.product`의 검수 대기 상태와 생성일 정렬

이번 작업은 다른 서비스 소유 스키마에 Flyway 마이그레이션을 추가하지 않는다. 운영 데이터 규모에서 실행 계획이나 응답 시간이 기준을 넘으면 User·Product 스키마 소유 팀에 인덱스 추가를 요청한다. 후보는 다음과 같다.

- `"user" (created_at)`
- `seller_settlement (status)` 또는 승인 대기 상태 partial index
- `product (created_at, id) WHERE status = 'PENDING_REVIEW' AND deleted_at IS NULL`

초기 구현에는 응답 캐시를 두지 않는다. 실제 호출 빈도와 쿼리 비용을 관찰한 뒤 짧은 TTL 캐시 도입 여부를 별도 결정한다.

## 게이트웨이

`VersionedServiceRoute`의 `admin-service` 경로에 `/admin/home`을 추가한다. `user-service`, `order-service`, `product-service` 라우트에는 같은 경로를 추가하지 않는다.

기존 `/admin/stats/users`, `/admin/orders/month`, `/admin/orders/weekend` 라우트와 컨트롤러는 유지한다.

## 검증 전략

어드민 홈은 별도 테스트를 추가하지 않고 기존 프로젝트 검증을 사용한다.

- 백엔드: `./gradlew :admin-service:check :apigateway:check`
- 프론트엔드: TypeScript 검사, 변경 파일 ESLint, Next.js 프로덕션 빌드

교차 스키마 SQL은 배포 후 어드민 홈 스모크 테스트로 실제 데이터 집계값과 응답 시간을 확인한다.

## 배포 순서

기존 API를 유지하므로 하위 호환 방식으로 배포한다.

1. `admin-service` 홈 API와 Gateway 경로를 배포한다.
2. 프론트 어드민 홈을 `/admin/home` 한 번 호출하도록 전환한다.
3. 홈 API 응답 시간, DB 오류율, 주요 집계값을 확인한다.
4. 기존 회원·주문 통계 API 제거 여부를 각 담당 팀의 후속 작업으로 전달한다.
5. 실행 계획이나 지연이 문제가 되면 스키마 소유 팀이 인덱스를 추가한다.

## 범위 밖

- 기존 회원·주문·정산·상품 API 삭제 또는 계약 변경
- `order-service`, `user-service`, `product-service`에 홈 전용 API 추가
- 서비스 간 REST, Feign, gRPC 통신 추가
- 다른 서비스 소유 스키마의 Flyway 마이그레이션 추가
- 캐시, 통계 스냅샷 테이블, 배치 사전 집계 도입
- 어드민 홈 이외 관리 화면의 UI 변경
