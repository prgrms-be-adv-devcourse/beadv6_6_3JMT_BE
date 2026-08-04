# 이번 주 인기 프롬프트 — 주간 트렌딩 설계

> ES 검색·추천 로드맵(#376~#383, [개요](2026-07-16-es-0-overview-design.md))과는 별개 이슈.
> RDB만으로 독립 구현 — ES 파이프라인 완료를 기다리지 않음.
>
> 📌 **미구현 (2026-08-04 기준)** — 이슈 #508은 열려 있고 담당자가 없다. product-service
> 코드에 이 설계에 해당하는 구현이 없다. 홈 화면은 여전히
> `GET /api/v2/products?sort=popular`(family 누적 salesCount, 시간 범위 없음)를 쓴다.
> **이 문서는 설계안이지 구현 기록이 아니다.**

## 1. 문제

홈 화면 "이번 주 인기 프롬프트"(`PopularGrid`, FE `app/page.tsx:206`)는
`GET /api/v2/products?sort=popular&size=8&productType=PROMPT`를 호출하는데,
`sort=popular`는 family 전체 누적 `salesCount` 합산 정렬이다(시간 범위 없음).

탐색(`/browse`) 페이지의 "인기순" 필터(`app/browse/page.tsx:49`)도 동일하게
`sort=popular`를 쓴다 — 즉 지금은 홈의 "이번 주"와 탐색의 "인기순"이
**완전히 같은 데이터**를 라벨만 다르게 보여주는 상태다. 예전에 많이 팔린 상품이
최근 판매가 0이어도 계속 상위 노출되고, 최근 잘 팔리는 신상품은 누적치가 작아
노출되지 않는다.

## 2. 스코프

- **BE만**: 새 정렬 옵션 `sort=weekly_popular`을 제공하는 데까지.
- FE가 `PopularGrid`의 `sort` 파라미터를 `popular` → `weekly_popular`로 바꾸는 건
  **범위 밖** — 한 줄짜리 후속 작업으로 남김(이 변경 전까지는 화면 동작 그대로 유지).
- ES 로드맵(#376~#383)과 무관하게 지금 RDB로 구현. **왜 ES를 안 쓰는지**는
  [8. ES와의 관계](#8-es와의-관계) 참고.

## 3. 신호와 시간창

- 신호: 지금 `sort=popular`와 동일하게 **판매(주문) 이벤트** 기반. 조회수·하이브리드는
  제외(단순화).
- 시간창: **롤링 7일**(오늘 기준 최근 7일, 매일 자동으로 밀림) — 캘린더 주(월~일 리셋)
  아님. 캘린더 주로 하면 월요일마다 데이터가 거의 없어 랭킹이 얇아지는 문제가 있어
  기각.

## 4. 데이터 모델

신규 테이블 `product_weekly_sales_bucket`:

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `family_root_id` | uuid | 상품 family 식별자 |
| `sale_date` | date | 판매/환불이 발생한 날짜 |
| `sale_count` | int | 그 날짜의 순 건수(+판매/-환불) |
| `updated_at` | timestamp | |

`unique(family_root_id, sale_date)` — family당 하루 최대 1행. 기존
`Product.salesCount`(전체 누적, family 단위)와 완전히 분리된 별도 카운터라
기존 로직·정산에 영향 없음.

## 5. 쓰기 경로

기존 `OrderEventHandler`의 결제/환불 처리 트랜잭션 안에 그대로 얹는다(멱등성
재사용, 원자적 커밋 — 별도 컨슈머보다 유리).

- **결제**: 오늘 날짜 버킷에 `+1` (`INSERT ... ON CONFLICT(family_root_id, sale_date)
  DO UPDATE SET sale_count = sale_count + 1`)
- **환불**: **환불 이벤트 발생일** 버킷에 `-1` (0 미만 방어). 원 구매 시점이 아니라
  환불이 접수된 날짜에 차감한다 — 다운로드 후 환불 불가 정책 덕에 구매→환불 간격이
  항상 짧아, 대부분 같은 7일 창 안에서 +1/-1이 상쇄된다.

환불을 완전히 무시하지 않는 이유: 무시하면 이번 주에 많이 팔리고 많이 환불된
상품도 "인기"로 노출돼 실제 품질과 괴리가 생긴다(실제로 이 트레이드오프를
지적받아 설계를 수정함).

## 6. 읽기 경로

- `ProductQueryService.normalizeSort`에 `"weekly_popular"` 허용값 추가(현재는
  인식 못 하는 값이 조용히 `popular`로 폴백됨 — 이 값만 명시적으로 분기).
- `ProductJpaRepository.findPublicProducts` JPQL에 `sale_date >= CURRENT_DATE - 6`
  조건의 서브쿼리 합산 정렬 분기 추가(도메인 포트 `ProductRepository` 시그니처는
  불변).
- **배치 불필요** — 매 요청마다 실시간 계산이라 매일 자동으로 창이 이동한다.
  family당 최대 7개 행만 합산하므로 지금의 무기한 누적 서브쿼리보다 오히려 가볍다.

## 7. 데이터 정리

정리 배치는 지금 만들지 않는다. family당 하루 최대 1행이라 증가 속도가 작고,
랭킹 쿼리는 `sale_date` 범위로 인덱스 스캔하므로 오래된 행이 쌓여도 조회 성능엔
영향 없음 — 순수 디스크 용량 문제라 당장 급하지 않음.

**후속 필요**: 용량이 실제 문제 되면 30일 초과 데이터를 지우는 `@Scheduled`
배치 추가(product-service에 `@Scheduled` 선례 없음 — 첫 도입 사례가 됨).

## 8. ES와의 관계

ES 로드맵(#376/#377)이 끝나도 "주간 판매량"이 공짜로 생기지 않는다 — ES 문서의
`salesCount`도 전체 누적값으로 설계돼 있어(시간창 없음), 결국 어딘가엔 지금과
똑같은 "날짜별 집계" 로직이 필요하다. ES 경유로 만들려면:

1. #376(색인 파이프라인)·#377(목록 API ES 전환) 완료를 기다려야 하고
2. 그 위에 "7일 집계 → ES 필드 부분 업데이트" 배치를 새로 설계해야 하며
3. 판매 사실이 RDB·ES 두 군데로 나뉘어 기록되는 동기화 문제가 생긴다

**후속 필요**: #377 완료 후, 이 RDB 테이블(`product_weekly_sales_bucket`)을
소스로 삼아 ES 상품 문서에 `weeklySalesCount` 필드 하나를 얹고(기존
`PRICE_CHANGED` 부분 갱신과 같은 방식), `weekly_popular` 정렬을 ES 경로로
자연스럽게 이관한다. RDB 테이블은 이관 후에도 소스로 계속 남는다.

## 9. 테스트

- 결제/환불 이벤트 처리 시 버킷 적재 통합 테스트(멱등성 포함)
- 7일 경계 밖 데이터가 랭킹 계산에서 제외되는지 검증
- `sort=weekly_popular` API 응답 검증(정렬 순서·`size` 파라미터)
