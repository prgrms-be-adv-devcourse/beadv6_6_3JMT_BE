# 정산 조회 공유 골격(Shared Foundation) 설계

- 이슈: #55 admin-settlements-query (정산 관리 화면 — 요약 카드 + 목록)
- 작성일: 2026-06-24
- 목적: **요약 조회**와 **목록 전체 조회**를 두 세션이 병렬로 구현할 수 있도록, 충돌 면을
  최소화하는 공유 골격(계약)을 먼저 깔고 커밋한다.

## 1. 배경 — 왜 골격을 먼저 까는가

정산 관리 화면 한 장에 두 기능이 있다.

- **요약 카드** = `Settlement`을 상태별로 그룹핑해 합계·건수 집계
- **목록** = 같은 `Settlement`을 상태 필터 + 페이징으로 조회

둘은 **같은 테이블·같은 상태 분류·같은 금액 의미**를 공유한다. 그냥 두 세션에서 동시에 짜면
(1) 공유 파일 머지 충돌, (2) 상태 매핑이 세션마다 달라져 카드 합계와 목록 필터가 어긋나는
버그가 난다. 이를 막기 위해 **공유 계약을 한 번에 확정·커밋**하고, 이후 두 세션은 각자
독립 파일만 채운다.

## 2. 공유 계약 ① — 파생 표시 상태 (frozen)

`Settlement`은 상태가 두 축이다.

- `settlementStatus`(승인 축): `PENDING_APPROVAL`, `SETTLEMENT_ON_HOLD`, `APPROVED`, `CANCELLED`
- `payoutStatus`(지급 축): `NOT_READY`, `READY`, `PAYOUT_REQUESTED`, `PAYOUT_ON_HOLD`, `PAID`

화면 탭 7종은 이 두 축을 합친 **파생 상태**이며 전체를 빠짐없이 분할한다
(탭 건수 합 2+2+4+0+2+4+2 = 전체 16 검증됨). 이를 `SettlementDisplayStatus` enum +
`from(settlementStatus, payoutStatus)` 파생 함수로 못 박는다. **요약은 이걸로 그룹핑,
목록은 이걸로 필터**한다.

| 표시 상태(enum) | 화면 라벨 | 파생 조건 (settlementStatus, payoutStatus) |
| --- | --- | --- |
| `WAITING` | 대기 | settlementStatus = PENDING_APPROVAL |
| `APPROVAL_ON_HOLD` | 승인 보류 | settlementStatus = SETTLEMENT_ON_HOLD |
| `APPROVED` | 승인 | settlementStatus = APPROVED & payout ∈ {NOT_READY, READY} |
| `PAYOUT_REQUESTED` | 지급 신청 | settlementStatus = APPROVED & payout = PAYOUT_REQUESTED |
| `PAYOUT_ON_HOLD` | 지급 보류 | settlementStatus = APPROVED & payout = PAYOUT_ON_HOLD |
| `PAID` | 지급 완료 | settlementStatus = APPROVED & payout = PAID |
| `CANCELLED` | 취소 | settlementStatus = CANCELLED |

> 이 표가 표시 상태의 **단일 출처**다. 목록의 상태 필터 SQL 술어, 요약의 집계 그룹핑 모두
> 이 표를 따른다. 두 세션은 이 매핑을 재해석하지 않는다.

## 3. 공유 계약 ② — 금액 의미 (frozen)

| 화면 컬럼 | `Settlement` 필드 |
| --- | --- |
| 총 거래액 | `totalAmount` |
| 수수료 | `feeTotalAmount` |
| 지급액 | `settlementTotalAmount` |
| 판매(건수) | `productCount` |
| 카드 금액 | 그룹별 `settlementTotalAmount` 합계 |

## 4. 파일 소유권 — frozen vs leaf

### 4-1. frozen (골격이 확정·커밋, 이후 두 세션 모두 건드리지 않음)

| 파일 | 내용 |
| --- | --- |
| `domain/model/enums/SettlementDisplayStatus.java` | 7종 enum + `from(settlementStatus, payoutStatus)` 파생 (완성) |
| `domain/model/Settlement.java` | `displayStatus()` 편의 메서드 추가 (enum 위임) |
| `application/usecase/GetSettlementSummaryUseCase.java` | 요약 인바운드 포트 (시그니처 동결) |
| `application/usecase/GetSettlementListUseCase.java` | 목록 인바운드 포트 (시그니처 동결) |
| `presentation/controller/SettlementQueryController.java` | GET 요약 / GET 목록 두 엔드포인트 + Swagger (완성) |

### 4-2. leaf (골격이 씨앗만 생성 → 이후 해당 세션이 소유·완성)

골격 커밋 시점에 컴파일·구동되도록 **스텁/기본 형태**로 만들어 두고, 각 세션이 자기 것만 채운다.
서로 다른 파일이라 충돌하지 않는다.

**요약 세션 소유:**
- `application/service/SettlementSummaryApplicationService.java` (스텁 → 집계 로직)
- `application/dto/SettlementSummaryResult.java` (씨앗 → 카드 구성 확정)
- `presentation/dto/response/SettlementSummaryResponse.java` (씨앗 → `from()`)
- `domain/repository/SettlementSummaryQueryRepository.java` (신규, 집계 포트)
- `infrastructure/persistence/SettlementSummaryQueryRepositoryAdapter.java` (+ JPA 집계 쿼리)

**목록 세션 소유:**
- `application/service/SettlementListApplicationService.java` (스텁 → 페이징 로직)
- `application/dto/SettlementListResult.java`, `SettlementListQuery.java` (씨앗 → 페이징/필터)
- `presentation/dto/response/SettlementListResponse.java` (씨앗 → `from()`)
- `domain/repository/SettlementListQueryRepository.java` (신규, 페이징 포트)
- `infrastructure/persistence/SettlementListQueryRepositoryAdapter.java` (+ JPA 페이징 쿼리)

> 기존 `SettlementRepository`(배치/커맨드용)는 **건드리지 않는다.** 조회는 별도 쿼리 포트로
> 분리한다(CQRS-lite). `clean-architecture.md` §4-1 "유스케이스 분리 기준"에 따라, 구현체가
> 다른(집계 vs 페이징) 두 조회는 포트도 구현도 분리한다.

### 4-3. 안 겹침 보장

골격 커밋 후, frozen 3종(컨트롤러·포트 2개·enum)은 동결된다. 컨트롤러는 포트 인터페이스와
`Response.from(Result)`에만 의존하므로, 각 세션이 자기 Result/Response 필드를 바꿔도 컨트롤러는
재컴파일된다(포트 시그니처·`from` 형태만 유지). 두 세션이 같은 파일을 동시에 수정하는 지점이 없다.

## 5. 골격 인터페이스 (동결 대상 시그니처)

```java
// domain/model/enums/SettlementDisplayStatus.java  (frozen)
public enum SettlementDisplayStatus {
    WAITING, APPROVAL_ON_HOLD, APPROVED, PAYOUT_REQUESTED, PAYOUT_ON_HOLD, PAID, CANCELLED;

    public static SettlementDisplayStatus from(SettlementStatus s, PayoutStatus p) {
        return switch (s) {
            case PENDING_APPROVAL -> WAITING;
            case SETTLEMENT_ON_HOLD -> APPROVAL_ON_HOLD;
            case CANCELLED -> CANCELLED;
            case APPROVED -> switch (p) {
                case NOT_READY, READY -> APPROVED;
                case PAYOUT_REQUESTED -> PAYOUT_REQUESTED;
                case PAYOUT_ON_HOLD -> PAYOUT_ON_HOLD;
                case PAID -> PAID;
            };
        };
    }
}

// application/usecase/GetSettlementSummaryUseCase.java  (frozen)
public interface GetSettlementSummaryUseCase {
    SettlementSummaryResult getSummary();
}

// application/usecase/GetSettlementListUseCase.java  (frozen)
public interface GetSettlementListUseCase {
    SettlementListResult getList(SettlementListQuery query);
}
```

- 컨트롤러 경로: `${api.init}/admin/settlements` (기존 배치 컨트롤러 `.../settlements/batch`와 동급),
  ADMIN 권한, `ApiResult<T>` 래퍼, `AuthHeaders` 사용 — 기존 `SettlementBatchController` 컨벤션 동일.
- 요약 `getSummary()`는 현재 화면상 필터가 없으므로 무인자. 필터가 생기면 별도 메서드/포트로 확장.
- 목록 `getList(SettlementListQuery)`의 `SettlementListQuery`는 상태 필터(`SettlementDisplayStatus`,
  null=전체) + 페이징(page, size)을 담는다. 구체 필드는 목록 세션이 확정(씨앗 제공).

## 6. 집계·필터 구현 가이드 (각 세션 plan으로 위임)

- **요약 집계 권장안:** `GROUP BY settlementStatus, payoutStatus`로 (상태쌍, count, sum) 조회 후
  Java에서 `SettlementDisplayStatus.from(s, p)`로 접어 카드 버킷으로 합산한다. SQL CASE 중복을 피하고
  §2 매핑과 자동 일치한다. 카드 4종(정산대기·승인완료·지급보류·지급완료) 그룹 구성은 요약 세션이 확정.
- **목록 필터 권장안:** 표시 상태 → (settlementStatus / payoutStatus) 술어 변환은 §2 표를 단일 출처로
  삼아 목록 쿼리에서 구성한다. 정렬·페이징은 Spring Data `Pageable`.

## 7. 추후 작업 (이번 범위 제외 — TODO)

- **판매자명/상점명 조회:** `Settlement`은 `sellerId`(UUID)만 보유한다. 목록 응답은 이번엔 `sellerId`만
  반환한다. 판매자명은 **이벤트로 타 서비스에 정보 요청**하는 방식으로 추후 채운다. 목록 세션이
  응답 DTO에 자리(예: `sellerName` nullable)만 두고, 연동 지점에 `// TODO:` 주석으로 남긴다.

## 8. 진행 순서

1. (이 세션) frozen 3종 + leaf 씨앗 파일 생성 → 컴파일·구동 확인 → 단독 커밋. ✅ 룰 커밋은 선행 완료.
2. 두 세션이 이 골격을 계약으로 삼아 각자 leaf 파일만 구현 (요약 집계 / 목록 페이징).
3. 각 세션은 구현 전 `test-first-unit-test`로 도메인·집계·필터 단위 테스트 먼저 작성.
