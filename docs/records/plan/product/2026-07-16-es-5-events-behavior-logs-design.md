# ES 검색·추천 설계 5 — 행동 이벤트 스키마·로그 인덱스

> 전체 그림·결정 로그는 [0 — 개요](2026-07-16-es-0-overview-design.md) 참고.
> Kafka 공통 규칙(EventMessage envelope, DLT, 멱등)은 `.claude/rules/kafka-event.md`를 따른다.

## 1. 퍼널의 구조 (order-service 무변경)

```
검색:  FE → 검색 API ──(searchId 발급)──→ behavior-events → 컨슈머 → behavior-logs
클릭:  FE → 상세 이동 시 ?searchId= 전달 → 상세 API가 PRODUCT_VIEWED에 실어 발행
구매:  order-service → order-events (기존 그대로) → 컨슈머가 purchase 문서로 번역 저장
```

- 검색↔클릭은 **searchId로 정확히** 연결. 클릭↔구매는 "같은 userId + 같은
  familyRootId의 최근 7일 내 마지막 searchId 달린 조회"로 사후 귀속(last-touch
  어트리뷰션) — 주문 흐름에 searchId를 끌고 다니지 않는다.
- FE 훅은 검색 결과 링크에 `?searchId=` 붙이기 한 줄 (결정 D6).

## 2. Kafka 계약 — 새 토픽 `behavior-events`

envelope는 EventMessage, key = userId(비로그인 null), `aggregateType: USER`.
**이벤트는 2종으로 시작** — 종류를 늘리는 것보다 필드를 늘리는 게 싸다.

```jsonc
// SEARCH_EXECUTED — 실제 검색 실행 시 (자동완성 타이핑은 로그 제외 — 문서 3)
{ "searchId": "S123", "userId": "U1|null",
  "query": "발표자료 이쁘게", "normalizedQuery": "발표자료 이쁘게",  // trim·소문자·공백정리
  "productTypeFilter": "PPT|null", "sort": "popular",
  "resultCount": 14, "tookMs": 38, "hybridUsed": true, "fallback": false,
  "topResultIds": ["F7", "F3", "..."] }   // 상위 10개 familyRootId — 노출 기록 + 클릭 위치 계산용

// PRODUCT_VIEWED — 상세 조회 시. searchId가 있으면 그게 곧 "검색 클릭"
{ "userId": "U1|null", "familyRootId": "F3", "productId": "P3v2",
  "searchId": "S123|null" }               // null = 홈·직접링크 등 비검색 유입
```

- **CLICK 이벤트는 따로 두지 않는다** — "클릭 = searchId 달린 VIEWED". 이벤트 종류와
  FE 부담이 줄고, 클릭 위치는 분석 때 `searchId → topResultIds` 순서에서 계산.
- 구매는 이 토픽에 없다 — 기존 order-events를 소비해 같은 로그 인덱스에
  `purchase` 문서로 번역 저장. 발행처를 늘리지 않는다.
- 비로그인도 userId 없이 로그를 남긴다 — searchId 덕에 검색→클릭 퍼널은 익명으로도
  이어지고, 개인화 대상에서만 빠진다.
- **스키마 진화 규칙 = 계약**: 필드는 추가만 허용, 이름 변경·삭제·의미 변경 금지.
  컨슈머는 모르는 필드 무시(컨벤션 §7과 동일 정신).

## 3. 찜(wish) 계약 — 확정, 구현은 user-service 합의 조건부 (결정 D8)

```jsonc
// user-events 토픽 (user-service 발행) — WISH_ADDED / WISH_REMOVED
{ "userId": "U1", "productId": "P3" }
// productId는 user-service가 아는 그대로 — family 해석은 product 쪽에서
// (getProductsByIds의 family resolve와 동일)
```

이벤트 하나로 두 소비가 동시에 열린다:

1. product 패키지 컨슈머 → `wish_count` 증감 — **salesCount와 동일 패턴**
   (order-events → salesCount 증감의 복사본, eventId 멱등 포함).
   쓰기는 gRPC 금지·Kafka 발행이 팀 규칙이므로 gRPC 대안은 없다.
2. search 패키지 컨슈머 → behavior-logs 기록 → 취향 벡터 가중 2 부활(yml 노브).

활성화 시 ES 쪽 변경은: 매핑에 wishCount 필드 추가(무비용) + 인기 공식 가중치
추가(등급 A) + 카운트 배치가 자동 반영. **설계 재작업 없음.**
user-service 쪽 발행 작업은 담당자와 별도 조율(모듈 경계 규칙).

## 4. ES 저장 — data stream `behavior-logs` + ILM

행동 로그는 "추가만 되고 오래된 건 버리는" 시계열 → data stream.

```
behavior-logs (data stream, 1 shard / 0 replica)
 ├─ 문서: { @timestamp, eventType, userId, searchId, normalizedQuery,
 │          familyRootId, productId, resultCount, tookMs, hybridUsed, fallback, ... }
 │        전부 keyword/date/integer/boolean — text(nori) 없음
 │        (로그는 검색 대상이 아니라 집계 대상)
 ├─ _id = eventId → Kafka 중복 전달 시 두 번째는 create 409 → "이미 처리됨" 스킵 (멱등)
 └─ ILM: rollover 7일 → 90일 경과 조각 자동 삭제
```

ILM은 단일 소형 노드의 생존 장치 — 로그는 무한히 자라므로 "오래된 인덱스 조각을
통째로 드롭"하는 자동 청소를 처음부터 건다. 90일이면 취향 벡터(반감기 14일)·
인기 검색어(7일 창)에 충분.

## 5. 소비자 배선도

```
behavior-logs ──┬─ 인기 검색어 배치(1시간): normalizedQuery terms 집계 → search-keywords (문서 3)
                ├─ 취향 프로필 배치(6시간): 유저별 가중평균 → user-profiles (문서 4)
                ├─ 검색 품질 일 배치: 검색어별 {검색수, 클릭수, 구매수, CTR, 전환율}
                │    → search-quality-daily — 랭킹 가중치 튜닝의 근거 데이터
                └─ Kibana (언제든 연결): 퍼널 대시보드, 0건 검색어 — "나중에 ELK"의 실체
```

## 6. 어트리뷰션 구현

구매↔클릭 연결은 실시간으로 하지 않는다. **일 배치**가 전일 purchase 문서를 돌며
"같은 userId + 같은 familyRootId의 최근 7일 내 마지막 searchId 달린 VIEWED"를 찾아
`search-quality-daily` 집계에 반영한다. 개별 문서 업데이트는 하지 않음 —
data stream 문서 수정은 어색하고, 실제 쓰임새(검색어별 전환율)는 집계값으로 충분 (YAGNI).

## 7. 기본값 (노브 등급은 문서 7)

로그 보존 90일 / rollover 7일 (등급 A — ILM 정책 수정) ·
어트리뷰션 창 7일 last-touch (등급 C — 바꾸면 과거 지표와 비교 불가) ·
topResultIds 10개
