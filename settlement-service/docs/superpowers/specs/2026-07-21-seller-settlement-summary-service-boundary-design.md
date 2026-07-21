# 판매자 정산 요약 서비스 경계 분리 설계 (#452)

## 1. 배경

현재 `GET /api/v2/sellers/me/settlements/summary`는 user-service의
`sellersettlement` 패키지에서 두 서비스의 값을 조합한다.

- Product gRPC `GetSellerStats`: `registeredPromptCount`, `totalSalesCount`
- `seller_settlement` 저장소 집계: `totalRevenueAmount`, `totalSettlementAmount`

프론트 `/shop`이 각 서비스의 공개 API를 직접 호출하는 구조로 바뀌면서 Product 통계를 Seller
Settlement가 대신 조회하고 합칠 이유가 없어졌다. Product 호출 실패를 0으로 숨기는 현재 방식은 실제
통계 0과 장애를 구분할 수 없고, Product 가용성이 정산 금액 조회 경로에 결합되는 문제도 남긴다.

## 2. 결정

기존 경로는 유지하고 응답 계약만 정산 소유 데이터로 축소한다.

```http
GET /api/v2/sellers/me/settlements/summary
```

```json
{
  "success": true,
  "data": {
    "totalRevenueAmount": 10449800,
    "totalSettlementAmount": 170000
  },
  "message": "success"
}
```

- `registeredPromptCount`, `totalSalesCount`는 응답에서 제거한다.
- 제거한 필드를 0이나 deprecated 필드로 유지하지 않는다.
- `/revenue-summary` 같은 새 경로를 추가하지 않는다.
- `GET /sellers/me/settlements` 목록 조회와 지급 신청 API는 변경하지 않는다.

Product는 등록 프롬프트 수와 누적 판매건수를 제공하는 별도 공개 API를 소유한다. 프론트는 Product
통계 API와 Seller Settlement summary API를 독립적으로 호출해 화면에서 조합한다.

## 3. 데이터 흐름

### 변경 전

```text
프론트 /shop
  -> Seller Settlement summary
       -> Product gRPC GetSellerStats
       -> seller_settlement 금액 집계
       -> 상품 통계 + 정산 금액을 한 응답으로 반환
```

### 변경 후

```text
프론트 /shop
  -> Product 공개 통계 API
       -> registeredPromptCount + totalSalesCount
  -> Seller Settlement summary
       -> seller_settlement 금액 집계
       -> totalRevenueAmount + totalSettlementAmount
```

두 요청은 서로 독립적이다. Product 통계 조회 실패는 Product 카드에만 영향을 주고, 정산 금액 조회
성공 여부에는 영향을 주지 않는다.

## 4. 백엔드 변경 범위

실제 seller summary 구현은 settlement-service 본체가 아니라
`user-service/src/main/java/com/prompthub/user/sellersettlement/`에 있다.

### 응답과 애플리케이션 서비스

- `SellerSettlementSummaryResponse`를 두 `BigDecimal` 필드만 가진 record로 축소한다.
- 정적 팩토리는 `of(BigDecimal totalRevenueAmount, BigDecimal totalSettlementAmount)` 형태로 바꾼다.
- `SellerSettlementApplicationService`에서 `ProductStatsClient` 주입과 호출을 제거한다.
- summary는 기존 `SellerSettlementRepository`의 다음 두 집계만 호출한다.
  - `sumTotalAmountBySeller(sellerId)`
  - `sumPaidSettlementAmountBySeller(sellerId)`
- `SellerSettlementUseCase#getMySummary(UUID)`와 컨트롤러 경로·메서드 시그니처는 유지한다.
- 컨트롤러 Swagger 설명은 Product 내부통신 설명을 제거하고 두 금액의 의미만 기술한다.

### 제거 대상

Seller Settlement가 Product 통계를 더 이상 소비하지 않으므로 다음 코드는 삭제한다.

- `application/client/ProductStatsClient.java`
- `application/dto/SellerProductStats.java`
- `infrastructure/grpc/ProductStatsGrpcClient.java`
- `infrastructure/grpc/ProductStatsGrpcClientConfig.java`
- `infrastructure/grpc/ProductStatsGrpcClientTest.java`

user-service의 wishlist도 별도 Product gRPC 클라이언트를 사용하므로 공통 Product 채널 설정이나
user-service 전체 gRPC 의존성은 이번 작업에서 제거하지 않는다.

## 5. 오류 처리

- Seller Settlement summary에는 외부 서비스 호출이 없어지므로 Product gRPC 예외와 0 폴백도 함께
  사라진다.
- 금액 집계의 저장소 오류는 현재 전역 예외 처리 흐름을 그대로 따른다. 새 오류 코드나 별도 폴백은
  추가하지 않는다.
- 프론트는 두 API의 로딩·오류 상태를 분리한다. 한 요청 실패 때문에 다른 서비스의 정상 값을 지우지
  않는다.

## 6. 테스트 전략

- `SellerSettlementSummaryResponse`의 record component 또는 JSON 필드가
  `totalRevenueAmount`, `totalSettlementAmount` 두 개뿐인지 계약 테스트로 고정한다.
- `SellerSettlementSummaryServiceTest`는 두 저장소 집계값이 응답에 정확히 매핑되는지 검증한다.
- Product 통계 mock, 호출 검증, 상품 통계 필드 assertion은 제거한다.
- 삭제되는 `ProductStatsGrpcClientTest`의 매핑·장애 폴백 테스트도 함께 제거한다.
- 기존 정산 목록·지급 신청 테스트를 실행해 summary 변경이 다른 seller settlement 기능에 영향을 주지
  않았는지 확인한다.
- user-service 모듈 테스트와 빌드로 삭제된 타입·스텁 참조가 남지 않았는지 검증한다.

## 7. 문서 동기화

구현과 같은 변경에서 현재 계약·통신 상태를 설명하는 다음 문서를 갱신한다.

- `docs/api-spec/settlement.md`
  - summary 예시와 필드표를 두 금액 필드로 축소
- `docs/grpc-contract-ownership.md`
  - user(sellersettlement)를 `GetSellerStats` 소비자로 표시한 현재 현황 제거
  - Product 소유 계약·서버 정리 여부는 Product 후속 작업임을 명시
- `docs/api-spec/product.md`
  - 내부 상품 수 API와 gRPC를 Seller Settlement가 소비한다는 낡은 설명 제거
  - Product 공개 통계 API의 상세 계약은 Product 작업에서 확정하므로 이번 문서에 추측해 추가하지 않음
- `settlement-service/docs/architecture/settlement-internal-comm-topology.md`
  - user sellersettlement의 Product gRPC 클라이언트와 대기 항목 제거
- `settlement-service/docs/architecture/integration-catalog.md`
  - seller summary의 Product 동기 조회 흐름을 프론트 직접 호출 경계로 변경
- `settlement-service/docs/trade-offs/internal-sync-transport.md`
  - Product 통계가 더 이상 정산 내부 동기 호출 사례가 아님을 반영

과거 결정과 당시 구현을 기록한 `docs/superpowers/specs/` 및 기존 이슈 문서는 수정하지 않는다.
`settlement-service/docs/기획문서.md`는 엔드포인트 존재만 기록하고 있어 경로가 유지되는 이번 변경에서는
수정하지 않는다.

## 8. 배포와 호환성

응답 필드 제거는 기존 소비자 관점에서 하위 호환이 아니다. 다만 0 필드 유지 단계는 두지 않기로
결정했다. 배포 순서는 다음 의존성을 만족해야 한다.

1. Product 공개 통계 API 계약과 구현을 준비한다.
2. 프론트 `/shop`이 Product 통계 API와 Seller Settlement summary를 각각 소비하도록 변경한다.
3. Seller Settlement summary에서 Product 필드와 gRPC 소비 코드를 제거한다.

동시 배포가 어렵다면 1, 2가 먼저 배포되어야 통계 카드 공백을 피할 수 있다. Seller Settlement의 두
금액 필드는 경로와 이름이 유지되므로 정산 금액 카드에는 별도 마이그레이션이 필요 없다.

## 9. 범위 밖

- Product 공개 통계 API 구현과 정확한 URL 확정
- 프론트 저장소 `/shop` 구현 변경
- Product가 소유한 `GetSellerStats` proto·서버 구현 삭제
- Seller Settlement 목록·지급 신청 동작 변경
- admin settlement API 변경
