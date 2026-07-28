# 판매자 정산 요약 서비스 경계 분리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/v2/sellers/me/settlements/summary`가 정산 소유 금액 두 개만 반환하도록 축소하고, Seller Settlement의 Product gRPC 집계 의존성을 제거한다.

**Architecture:** user-service의 `sellersettlement` summary는 `seller_settlement` 저장소 집계만 사용한다. 등록 프롬프트 수와 누적 판매건수는 Product 공개 API가 소유하고 프론트가 별도로 호출한다. 정산 목록·지급 신청·admin settlement와 Product 소유 proto·서버는 변경하지 않는다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, Spring gRPC, JUnit 5, Mockito, AssertJ, Gradle

## Global Constraints

- 작업 기준은 `#452 (이슈)`와 `feat/#452-remove-product-stats-aggregation (브랜치)`다.
- 설계 원본은 `settlement-service/docs/superpowers/specs/2026-07-21-seller-settlement-summary-service-boundary-design.md`다.
- 기존 `/api/v2/sellers/me/settlements/summary` 경로와 `SellerSettlementUseCase#getMySummary(UUID)` 시그니처를 유지한다.
- summary 응답은 `totalRevenueAmount`, `totalSettlementAmount`만 포함한다. 제거 필드를 0이나 deprecated 필드로 남기지 않는다.
- `/revenue-summary` 등 새 엔드포인트를 추가하지 않는다.
- Product 공개 통계 API, 프론트 `/shop`, `grpc/product/product_query.proto`, product-service 서버는 범위 밖이다.
- user-service의 wishlist와 seller gRPC 서버가 별도 로컬 proto를 사용하므로 protobuf 플러그인과 gRPC 서버·클라이언트 의존성은 유지한다.
- 루트 `docs/` 변경은 사용자가 명시적으로 승인했다. Product 공개 API의 URL·응답은 Product 작업에서 확정하므로 추측해 문서화하지 않는다.
- `SellerSettlementController.java`에는 작업 시작 전부터 사용자 소유의 들여쓰기 변경이 있다. reset·stash하지 않고, 커밋할 때 summary Swagger 설명 hunk만 선택적으로 stage한다.
- `GET /sellers/me/settlements`, 지급 신청, admin settlement의 코드와 계약은 변경하지 않는다.

---

## File Map

### Create

- `user-service/src/test/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementSummaryResponseTest.java` — 응답 필드 계약을 고정한다.

### Modify

- `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementSummaryResponse.java` — 상품 통계 필드를 제거하고 금액 두 필드만 노출한다.
- `user-service/src/main/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementApplicationService.java` — Product 포트 주입·호출을 제거한다.
- `user-service/src/test/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementSummaryServiceTest.java` — 정산 저장소 집계만 검증한다.
- `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/controller/SellerSettlementController.java` — summary Swagger 설명을 실제 계약에 맞춘다.
- `user-service/build.gradle` — Seller Settlement 전용 루트 `grpc/product` proto sourceSet만 제거한다.
- `docs/api-spec/settlement.md` — 공개 summary 응답 계약을 갱신한다.
- `docs/api-spec/product.md` — Seller Settlement가 Product 내부 통계를 소비한다는 설명을 제거한다.
- `docs/grpc-contract-ownership.md` — `GetSellerStats`의 Seller Settlement 소비 현황을 종료 상태로 바꾼다.
- `settlement-service/docs/architecture/settlement-internal-comm-topology.md` — user sellersettlement의 Product gRPC 클라이언트와 대기 항목을 제거한다.
- `settlement-service/docs/architecture/integration-catalog.md` — 상품 통계 조합 책임을 프론트 경계로 옮긴다.
- `settlement-service/docs/trade-offs/internal-sync-transport.md` — 현재 정산 내부 동기 호출 사례를 order 원천 조회로 한정한다.

### Delete

- `user-service/src/main/java/com/prompthub/user/sellersettlement/application/client/ProductStatsClient.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/application/dto/SellerProductStats.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClient.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClientConfig.java`
- `user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClientTest.java`

---

### Task 1: 정산 소유 금액만 노출하는 summary 계약

**Files:**
- Create: `user-service/src/test/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementSummaryResponseTest.java`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementSummaryResponse.java:1-28`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementApplicationService.java:1-58`
- Modify: `user-service/src/test/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementSummaryServiceTest.java:1-52`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/controller/SellerSettlementController.java:67-81`

**Interfaces:**
- Consumes: `SellerSettlementRepository.sumTotalAmountBySeller(UUID) -> BigDecimal`, `sumPaidSettlementAmountBySeller(UUID) -> BigDecimal`
- Produces: `SellerSettlementSummaryResponse(BigDecimal totalRevenueAmount, BigDecimal totalSettlementAmount)`와 기존 `GET /api/v2/sellers/me/settlements/summary`

- [ ] **Step 1: 새 응답 계약을 고정하는 실패 테스트 작성**

```java
package com.prompthub.user.sellersettlement.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("판매자 정산 요약 응답 계약")
class SellerSettlementSummaryResponseTest {

    @Test
    @DisplayName("정산 소유 금액 두 필드만 노출한다")
    void exposesOnlySettlementOwnedAmountFields() {
        assertThat(SellerSettlementSummaryResponse.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("totalRevenueAmount", "totalSettlementAmount");
    }
}
```

- [ ] **Step 2: 계약 테스트가 현재 네 필드 때문에 실패하는지 확인**

Run:

```bash
./gradlew :user-service:test --tests com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementSummaryResponseTest
```

Expected: FAIL. 실제 record component에 `registeredPromptCount`, `totalSalesCount`가 앞에 존재한다는 assertion 차이가 출력된다.

- [ ] **Step 3: 응답 record를 금액 두 필드로 축소**

`SellerSettlementSummaryResponse.java`를 다음 최종 내용으로 바꾼다.

```java
package com.prompthub.user.sellersettlement.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "판매자 정산 금액 요약 응답")
public record SellerSettlementSummaryResponse(
        @Schema(description = "누적 총 거래액", example = "10449800")
        BigDecimal totalRevenueAmount,

        @Schema(description = "누적 정산 지급 완료 금액", example = "170000")
        BigDecimal totalSettlementAmount
) {

    public static SellerSettlementSummaryResponse of(
            BigDecimal totalRevenueAmount, BigDecimal totalSettlementAmount) {
        return new SellerSettlementSummaryResponse(totalRevenueAmount, totalSettlementAmount);
    }
}
```

- [ ] **Step 4: 애플리케이션 서비스에서 Product 통계 의존과 호출 제거**

다음 import를 제거한다.

```java
import com.prompthub.user.sellersettlement.application.client.ProductStatsClient;
import com.prompthub.user.sellersettlement.application.dto.SellerProductStats;
```

필드는 저장소 하나만 남긴다.

```java
private final SellerSettlementRepository sellerSettlementRepository;
```

`getMySummary`는 다음과 같이 바꾼다.

```java
@Override
@Transactional(readOnly = true)
public SellerSettlementSummaryResponse getMySummary(UUID sellerId) {
    BigDecimal totalRevenueAmount = sellerSettlementRepository.sumTotalAmountBySeller(sellerId);
    BigDecimal totalSettlementAmount = sellerSettlementRepository.sumPaidSettlementAmountBySeller(sellerId);
    return SellerSettlementSummaryResponse.of(totalRevenueAmount, totalSettlementAmount);
}
```

- [ ] **Step 5: 서비스 테스트를 정산 금액 집계 전용으로 변경**

`SellerSettlementSummaryServiceTest.java`를 다음 최종 내용으로 바꾼다.

```java
package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementSummaryResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerSettlementSummaryServiceTest {

    @Mock
    private SellerSettlementRepository sellerSettlementRepository;

    @InjectMocks
    private SellerSettlementApplicationService service;

    @Test
    @DisplayName("요약: 정산 저장소의 누적 거래액과 지급 완료액을 반환한다")
    void getMySummary_returnsSettlementOwnedAmountSums() {
        // given
        UUID sellerId = UUID.randomUUID();
        given(sellerSettlementRepository.sumTotalAmountBySeller(sellerId))
                .willReturn(new BigDecimal("10449800"));
        given(sellerSettlementRepository.sumPaidSettlementAmountBySeller(sellerId))
                .willReturn(new BigDecimal("170000"));

        // when
        SellerSettlementSummaryResponse response = service.getMySummary(sellerId);

        // then
        assertThat(response.totalRevenueAmount()).isEqualByComparingTo("10449800");
        assertThat(response.totalSettlementAmount()).isEqualByComparingTo("170000");
    }
}
```

- [ ] **Step 6: summary Swagger 설명에서 Product 집계 설명 제거**

`SellerSettlementController#getMySummary`의 `@Operation`을 다음과 같이 바꾼다. 경로와 메서드 본문은 유지한다.

```java
@GetMapping("/summary")
@Operation(summary = "판매자 정산 금액 요약 조회",
        description = "본인의 누적 총 거래액과 누적 정산 지급 완료 금액을 조회합니다. SELLER 권한이 필요합니다.")
```

- [ ] **Step 7: 응답 계약과 서비스 테스트 통과 확인**

Run:

```bash
./gradlew :user-service:test \
  --tests com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementSummaryResponseTest \
  --tests com.prompthub.user.sellersettlement.application.service.SellerSettlementSummaryServiceTest
```

Expected: BUILD SUCCESSFUL, 두 테스트 클래스 모두 통과.

- [ ] **Step 8: 의도한 코드와 테스트를 stage**

```bash
git add user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementSummaryResponse.java
git add user-service/src/main/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementApplicationService.java
git add user-service/src/test/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementSummaryServiceTest.java
git add user-service/src/test/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementSummaryResponseTest.java
git add -p user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/controller/SellerSettlementController.java
```

`git add -p`에서는 summary `@Operation` 문구 변경만 stage한다. 들여쓰기만 달라진 기존 hunk는 stage하지 않는다. 한 hunk에 섞이면 `s`로 나누고, 더 나뉘지 않으면 `e`로 patch를 편집해 문구 변경 줄만 남긴다.

- [ ] **Step 9: stage 범위와 whitespace 확인**

```bash
git diff --cached --check
git diff --cached --name-status
git diff --cached -- user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/controller/SellerSettlementController.java
```

Expected: 새 테스트 1개와 수정 파일 4개만 stage. Controller staged diff에는 summary 설명 변경만 있고 기존 들여쓰기 변경은 없다.

- [ ] **Step 10: 응답 계약 변경 커밋**

```bash
git commit -m "feat: 판매자 정산 요약을 금액 전용 응답으로 축소 (#452)"
```

---

### Task 2: 사용하지 않는 Product gRPC 소비 코드와 스텁 생성 설정 제거

**Files:**
- Modify: `user-service/build.gradle:50-61`
- Delete: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/client/ProductStatsClient.java`
- Delete: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/dto/SellerProductStats.java`
- Delete: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClient.java`
- Delete: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClientConfig.java`
- Delete: `user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClientTest.java`

**Interfaces:**
- Consumes: Task 1에서 Product 통계 호출이 제거된 `SellerSettlementApplicationService`
- Produces: Seller Settlement 패키지에서 Product gRPC 포트·어댑터·스텁 빈이 완전히 사라진 컴파일 가능한 user-service

- [ ] **Step 1: 제거 전 잔존 참조 기준선 확인**

```bash
rg -n 'ProductStatsClient|SellerProductStats|ProductStatsGrpcClient|GetSellerStats|ProductQueryServiceGrpc|grpc/product' \
  user-service/src/main/java/com/prompthub/user/sellersettlement \
  user-service/src/test/java/com/prompthub/user/sellersettlement \
  user-service/build.gradle
```

Expected: 제거 대상 포트·DTO·클라이언트·설정·테스트와 `build.gradle`의 `grpc/product` sourceSet이 확인된다. Task 1 완료 후 애플리케이션 서비스와 summary 응답에서는 결과가 없어야 한다.

- [ ] **Step 2: Product 통계 전용 파일 다섯 개 삭제**

```diff
*** Begin Patch
*** Delete File: user-service/src/main/java/com/prompthub/user/sellersettlement/application/client/ProductStatsClient.java
*** Delete File: user-service/src/main/java/com/prompthub/user/sellersettlement/application/dto/SellerProductStats.java
*** Delete File: user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClient.java
*** Delete File: user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClientConfig.java
*** Delete File: user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClientTest.java
*** End Patch
```

- [ ] **Step 3: user-service의 Seller Settlement 전용 루트 proto sourceSet 제거**

`user-service/build.gradle`에서 다음 블록만 삭제한다.

```gradle
// 루트 공유 gRPC 계약을 이 모듈 proto 소스에 더한다.
// - grpc/product: 소유 product(이 모듈은 소비자 미러).
// docs/grpc-contract-ownership.md 참고.
sourceSets {
    main {
        proto {
            srcDir "${rootProject.projectDir}/grpc/product"
        }
    }
}
```

protobuf plugin, `spring-boot-starter-grpc-server`, `spring-boot-starter-grpc-client`, `grpc-stub`, `grpc-protobuf`는 그대로 둔다. `user-service/src/main/proto/product_service.proto`와 `product_seller_query.proto`도 유지한다.

- [ ] **Step 4: Seller Settlement의 Product 통계 참조가 사라졌는지 확인**

```bash
rg -n 'ProductStatsClient|SellerProductStats|ProductStatsGrpcClient|GetSellerStats|ProductQueryServiceGrpc|grpc/product' \
  user-service/src/main/java/com/prompthub/user/sellersettlement \
  user-service/src/test/java/com/prompthub/user/sellersettlement \
  user-service/build.gradle
```

Expected: exit 1, 출력 없음.

- [ ] **Step 5: user-service main/test 컴파일 확인**

```bash
./gradlew :user-service:compileJava :user-service:compileTestJava
```

Expected: BUILD SUCCESSFUL. wishlist와 seller gRPC 계약의 생성 클래스도 정상 컴파일된다.

- [ ] **Step 6: 삭제와 Gradle 변경만 stage하고 확인**

```bash
git add user-service/build.gradle
git add user-service/src/main/java/com/prompthub/user/sellersettlement/application/client/ProductStatsClient.java
git add user-service/src/main/java/com/prompthub/user/sellersettlement/application/dto/SellerProductStats.java
git add user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClient.java
git add user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClientConfig.java
git add user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClientTest.java
git diff --cached --check
git diff --cached --name-status
```

Expected: `user-service/build.gradle` 수정 1개와 삭제 5개만 stage. Controller의 기존 들여쓰기 변경은 포함되지 않는다.

- [ ] **Step 7: Product gRPC 소비 제거 커밋**

```bash
git commit -m "refactor: 판매자 정산 Product gRPC 의존 제거 (#452)"
```

---

### Task 3: 공개 API 문서를 두 서비스의 독립 계약으로 갱신

**Files:**
- Modify: `docs/api-spec/settlement.md:132-159`
- Modify: `docs/api-spec/product.md:720-740,797-803`

**Interfaces:**
- Consumes: Task 1의 두 필드 Seller Settlement summary 계약
- Produces: 프론트가 Seller Settlement와 Product를 따로 호출한다는 현재 공개 계약 문서

- [ ] **Step 1: 정산 summary API 예시와 필드표 교체**

`docs/api-spec/settlement.md`의 seller summary 섹션을 다음 내용으로 바꾼다.

````markdown
### GET /sellers/me/settlements/summary — 정산 금액 요약 조회

- 인증: 필요
- 필요 역할: SELLER

등록 프롬프트 수와 누적 판매건수는 Product 공개 API가 소유하며, 프론트가 별도로 조회한다.
이 엔드포인트는 Seller Settlement가 소유한 누적 거래액과 지급 완료액만 반환한다.

#### Response 200

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

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `totalRevenueAmount` | BigDecimal | 누적 총 거래액 |
| `totalSettlementAmount` | BigDecimal | 누적 정산 지급 완료 금액 |
````

- [ ] **Step 2: Product 내부 count API의 낡은 Seller Settlement 호출 설명 제거**

`docs/api-spec/product.md`의 내부 count 섹션을 현재 구현 계약까지 포함해 다음처럼 바꾼다.

````markdown
### GET /internal/products/count — 판매자 상품 통계 내부 조회

- 용도: Product 서비스 내부 판매자 상품 family 수·판매건수 집계
- 비고: Seller Settlement summary는 #452 이후 이 내부 API나 Product gRPC를 소비하지 않는다.
- 공개 Seller 통계 API의 경로와 응답은 Product 후속 작업에서 정의한다.

#### Query Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| sellerId | UUID | Y | 판매자 ID |

#### Response

```json
{
  "sellerId": "uuid",
  "productCount": 12,
  "salesCount": 1342
}
```
````

- [ ] **Step 3: Product gRPC 제공 현황에서 Seller Settlement 소비자 제거**

`ProductQueryService`의 `GetSellerStats` 행은 서버 구현 현황으로 남기되 다음 주석을 바로 아래에 둔다.

```markdown
#### `ProductQueryService`

| rpc | 요청 | 응답 |
|---|---|---|
| `GetSellerStats` | `seller_id` | `seller_id`, `product_count`, `sales_count` |

> #452 이후 user-service `sellersettlement`는 `GetSellerStats`를 소비하지 않는다. RPC 유지·삭제와
> 공개 Seller 통계 API 전환은 Product 후속 작업에서 결정한다.
```

- [ ] **Step 4: 공개 계약 문서에서 제거된 필드와 내부 집계 설명 검사**

```bash
rg -n 'registeredPromptCount|totalSalesCount|Product 서비스 gRPC 조회로 채우며|settlement-service → product-service|판매자 정산 요약 조회 시' \
  docs/api-spec/settlement.md docs/api-spec/product.md
```

Expected: exit 1, 출력 없음. `GetSellerStats` 자체는 Product 서버 현황과 후속 작업 설명에 남을 수 있다.

- [ ] **Step 5: Markdown whitespace와 diff 확인**

```bash
git diff --check -- docs/api-spec/settlement.md docs/api-spec/product.md
git diff -- docs/api-spec/settlement.md docs/api-spec/product.md
```

Expected: 정산 summary 섹션과 Product 소비자 설명만 변경. Product 공개 API URL을 새로 만들지 않는다.

- [ ] **Step 6: 공개 API 문서 커밋**

```bash
git add docs/api-spec/settlement.md docs/api-spec/product.md
git commit -m "docs: 판매자 정산 요약 API 계약 갱신 (#452)"
```

---

### Task 4: gRPC 소유·내부통신 아키텍처 문서를 현재 구조로 갱신

**Files:**
- Modify: `docs/grpc-contract-ownership.md`
- Modify: `settlement-service/docs/architecture/settlement-internal-comm-topology.md`
- Modify: `settlement-service/docs/architecture/integration-catalog.md`
- Modify: `settlement-service/docs/trade-offs/internal-sync-transport.md`

**Interfaces:**
- Consumes: Task 2에서 Seller Settlement의 `GetSellerStats` 소비자가 제거된 상태
- Produces: 정산 내부 동기 호출은 settlement→order 원천 조회만 남고, Product 통계는 프론트 조합임을 설명하는 현재 아키텍처 문서

- [ ] **Step 1: 루트 gRPC 계약 소유 현황에서 Seller Settlement 소비 종료 반영**

`docs/grpc-contract-ownership.md`의 현재 정산 관련 계약 표는 다음처럼 바꾼다.

```markdown
## 5. 현재 정산 관련 계약 현황

| 계약(rpc) | 요청자(client) | 서버(owner) | 위치 | 비고 |
| --- | --- | --- | --- | --- |
| `GetSettleableLines`(정산 원천) | settlement | **order** | `grpc/order/order_query.proto` | 루트 공유 이관 완료. order 서버 미구현이라 정산 클라이언트가 유일본 |

> **소비 종료:** `GetSellerStats`는 #452에서 user-service `sellersettlement` 소비자가 제거됐다.
> `grpc/product/product_query.proto`와 Product 서버의 RPC 유지·삭제는 계약 소유자인 Product 후속
> 작업에서 결정한다. Seller Settlement는 더 이상 이 계약의 클라이언트가 아니다.
```

`## 3. 디렉토리 레이아웃`의 Product 설명은 다음처럼 바꾼다.

```markdown
└── product/
    └── product_query.proto  ← Product 소유 공유 계약. Seller Settlement는 #452 이후 GetSellerStats를 소비하지 않음
```

기존 `product 서버 원본이 잔존하는 이유` 절은 다음 후속 정리 절로 교체한다.

```markdown
### Product 계약 후속 정리

루트 `grpc/product/product_query.proto`에는 Product가 제공하는 다른 RPC도 함께 있으므로 파일 전체를
이번 작업에서 삭제하지 않는다. `GetSellerStats` RPC와 Product 서버 구현의 유지·삭제, 공개 Seller 통계
API 전환은 서버 소유자인 Product가 결정한다. user-service는 #452에서 루트 `grpc/product` sourceSet
참조를 제거한다.
```

- [ ] **Step 2: 통신 현황판에서 user sellersettlement Product gRPC 블록 제거**

`settlement-internal-comm-topology.md`의 user-service `sellersettlement` gRPC 설명을 다음 상태로 바꾼다.

```markdown
**gRPC 클라이언트 — 없음.**

- #452에서 판매자 정산 요약의 Product `GetSellerStats` 클라이언트·포트·설정을 제거했다.
- summary는 `seller_settlement` 저장소의 누적 거래액·지급 완료액만 집계한다.
- 등록 프롬프트 수와 누적 판매건수는 프론트가 Product 공개 API를 별도로 호출한다.
```

`## 4. 요청 대기 · 미완 항목`에서 `user sellersettlement → product GetSellerStats` 항목을 삭제하고, 남은 번호를 연속되게 정리한다.

- [ ] **Step 3: 통합 카탈로그의 Product 참고 조회를 프론트 조합 경계로 변경**

`integration-catalog.md`에서 seller summary가 Product gRPC를 호출한다는 데이터 행과 현재 상태 설명을 제거하고 §2-2를 다음 내용으로 교체한다.

```markdown
### 2-2. Product — 판매자 상품 통계 조합 경계

#452 이후 Seller Settlement 백엔드는 등록 상품 수와 판매건수를 조회하지 않는다. user-service
`sellersettlement`의 Product 통계 gRPC 클라이언트와 `GetSellerStats` 소비 계약은 제거된다.

Product는 등록 프롬프트 수와 누적 판매건수를 제공하는 공개 API를 별도 작업에서 정의한다. 프론트
`/shop`은 Product 통계 API와 Seller Settlement summary를 독립적으로 호출하며, Seller Settlement
summary는 `totalRevenueAmount`, `totalSettlementAmount`만 반환한다.
```

구현 현황 요약의 관련 행은 다음과 같이 바꾼다.

```markdown
| 등록 상품 수 / 판매건수 | **Seller Settlement 내부 조회 제거(#452).** Product 공개 API를 프론트가 직접 호출하는 구조로 전환. 정확한 Product API 계약은 Product 후속 작업 |
```

- [ ] **Step 4: 동기 호출 trade-off 문서의 현재 구조와 결론 갱신**

`internal-sync-transport.md`의 첫 배경 문단을 다음 내용으로 바꾼다.

```markdown
정산 도메인은 정산 대상을 계산할 때 order의 결제·환불 라인이 필요하다. 이 원천 데이터는 order가
소유하므로 settlement-service가 배치 실행 중 동기로 조회한다. 이 문서는 정산 정확성에 필요한 내부
동기 조회를 REST로 구현할지 gRPC로 구현할지 다룬다.

판매자 화면의 등록 상품 수·판매건수는 #452에서 정산 백엔드 조합 대상에서 제외됐다. Product 공개
API와 Seller Settlement summary를 프론트가 각각 호출하므로 이 문서의 내부 전송 선택 대상이 아니다.
```

`internal-sync-transport.md`의 `지금 구조`를 다음 내용으로 교체한다.

````markdown
## 지금 구조

정산 도메인에서 현재 유지하는 내부 동기 호출은 settlement-service가 order-service에서 정산 원천
라인을 가져오는 경로다.

- **settlement-service(정산 본체)** — `OrderSettlementQueryClient` → order 서비스:
  배치 시점에 기간의 정산 라인(`GetSettleableLines`)을 bulk로 가져와
  `settlement_source_line`에 적재한다.

```text
settlement-service ─gRPC(blocking)─▶ order-service (정산 원천 라인 pull, #260)
```

판매자 정산 요약의 상품 수·판매건수는 #452에서 내부 동기 호출 대상에서 제외됐다. Product가 공개
API를 제공하고 프론트가 Seller Settlement summary와 별도로 호출한다.
````

`정산에 맞는 선택` 절은 다음 내용으로 교체한다.

```markdown
## 정산에 맞는 선택

정산 금액과 대상을 결정하는 order 원천 라인은 백엔드 서비스 간 내부 계약이며 기간 단위 bulk 조회다.
따라서 `.proto`로 타입을 강제하고 한 요청에 여러 라인을 전달하는 gRPC를 유지한다. 전송 세부사항은
`OrderSettlementQueryPort` 뒤에 두어 애플리케이션 계층이 gRPC에 직접 의존하지 않게 한다.

반대로 화면 조합용 Product 통계는 브라우저가 소비하는 공개 데이터다. Product가 REST API를 소유하고
프론트가 직접 호출하는 편이 서비스 경계와 장애 범위를 더 분명하게 만든다. Seller Settlement는 자기
저장소의 금액만 응답한다.
```

`실패를 어떻게 다루나` 절은 다음 내용으로 축소한다.

```markdown
## 실패를 어떻게 다루나

order 정산 라인 조회는 정산 금액과 대상에 직접 영향을 준다. 실패를 빈 결과로 바꾸면 조용한 0건
정산이 발생할 수 있으므로 `OrderSettlementQueryClient`는
`SettlementException(SETTLEMENT_SOURCE_QUERY_FAILED)`로 배치를 중단한다.

Product 상품 통계 실패는 정산 백엔드의 실패 정책 대상이 아니다. 프론트가 Product와 Seller
Settlement 요청 상태를 분리해 한쪽 실패가 다른 쪽 정상 값을 가리지 않게 처리한다.
```

`정리` 절은 다음 결론으로 바꾼다.

```markdown
## 정리

- 정산 정확성에 필요한 order 원천 라인은 내부 gRPC로 조회하고 실패 시 배치를 중단한다.
- 화면 조합용 Product 통계는 Product 공개 REST API가 소유하고 프론트가 직접 호출한다.
- Seller Settlement summary는 자기 저장소의 금액만 반환해 Product 가용성과 분리한다.
```

- [ ] **Step 5: 현재 아키텍처 문서의 제거 대상 표현 검사**

```bash
rg -n 'ProductStatsGrpcClient|SellerProductStats\.empty|user sellersettlement ─gRPC|등록 상품 수 / 판매건수.*동기 조회' \
  docs/grpc-contract-ownership.md \
  settlement-service/docs/architecture/settlement-internal-comm-topology.md \
  settlement-service/docs/architecture/integration-catalog.md \
  settlement-service/docs/trade-offs/internal-sync-transport.md
```

Expected: exit 1, 출력 없음. `GetSellerStats`는 소비 종료와 Product 후속 정리 문맥에서만 남을 수 있다.

- [ ] **Step 6: 아키텍처 문서 diff 검사**

```bash
git diff --check -- \
  docs/grpc-contract-ownership.md \
  settlement-service/docs/architecture/settlement-internal-comm-topology.md \
  settlement-service/docs/architecture/integration-catalog.md \
  settlement-service/docs/trade-offs/internal-sync-transport.md
```

Expected: 출력 없음, exit 0.

- [ ] **Step 7: 아키텍처 문서 커밋**

```bash
git add docs/grpc-contract-ownership.md
git add settlement-service/docs/architecture/settlement-internal-comm-topology.md
git add settlement-service/docs/architecture/integration-catalog.md
git add settlement-service/docs/trade-offs/internal-sync-transport.md
git commit -m "docs: 판매자 정산 Product 통신 제거 현행화 (#452)"
```

---

### Task 5: 전체 회귀·잔존 참조 검증

**Files:**
- Verify only: Task 1-4의 전체 변경

**Interfaces:**
- Consumes: 금액 전용 summary, 삭제된 Product gRPC 소비 코드, 갱신된 공식 문서
- Produces: user-service 전체 build가 통과하고 범위 밖 변경이 없는 검증 결과

- [ ] **Step 1: summary 핵심 테스트를 다시 실행**

```bash
./gradlew :user-service:test \
  --tests com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementSummaryResponseTest \
  --tests com.prompthub.user.sellersettlement.application.service.SellerSettlementSummaryServiceTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: user-service 전체 build 실행**

```bash
./gradlew :user-service:build
```

Expected: BUILD SUCCESSFUL. 모든 user-service 테스트와 main checkstyle·컴파일 작업 완료.

- [ ] **Step 3: live Seller Settlement 코드에 Product 통계 참조가 없는지 검사**

```bash
rg -n 'ProductStatsClient|SellerProductStats|ProductStatsGrpcClient|GetSellerStats|registeredPromptCount|totalSalesCount' \
  user-service/src/main/java/com/prompthub/user/sellersettlement \
  user-service/src/test/java/com/prompthub/user/sellersettlement
```

Expected: exit 1, 출력 없음.

- [ ] **Step 4: 제거된 파일과 루트 proto sourceSet 부재 확인**

```bash
git ls-files \
  user-service/src/main/java/com/prompthub/user/sellersettlement/application/client/ProductStatsClient.java \
  user-service/src/main/java/com/prompthub/user/sellersettlement/application/dto/SellerProductStats.java \
  user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClient.java \
  user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClientConfig.java \
  user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/grpc/ProductStatsGrpcClientTest.java
rg -n 'srcDir.*grpc/product' user-service/build.gradle
```

Expected: 두 명령 모두 출력 없음.

- [ ] **Step 5: 최종 diff와 사용자 소유 변경 보존 확인**

```bash
git diff --check
git status --short --branch
git log --oneline --decorate -5
```

Expected:

- `git diff --check` 출력 없음.
- Task 1-4 커밋이 현재 브랜치에 존재한다.
- 구현에서 만든 파일이 stage되지 않은 채 남아 있지 않는다.
- 기존 `SellerSettlementController.java` 들여쓰기 변경을 의도적으로 커밋하지 않았다면 해당 파일만 unstaged로 남는다.
- push와 PR 생성은 수행하지 않는다.
