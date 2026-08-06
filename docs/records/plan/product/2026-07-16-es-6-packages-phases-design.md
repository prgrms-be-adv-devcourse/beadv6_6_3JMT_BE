# ES 검색·추천 설계 6 — 패키지 구조·Phase 로드맵·테스트

> 전체 그림·결정 로그는 [0 — 개요](2026-07-16-es-0-overview-design.md) 참고.
>
> ⚠️ **§1(컨트롤러 배치)·§2(의존 방향) 대체됨 (2026-07-27)** — 의존 방향이 뒤집혔다.
>
> ```
> 원안:  recommendation → search → product
> 확정:  product → search,  product → recommendation
> ```
>
> 컨트롤러 이관(`GET /products` → search, related → recommendation)은 하지 않는다. 공개
> API의 주체는 `ProductController` 단독이고, search·recommendation은 포트 뒤에서 기술
> 구현만 담당한다(D11). §2의 "product는 검색·추천의 존재를 모른다"는 전제는 폐기한다 —
> product는 두 포트 인터페이스를 알되 ES·임베딩·kNN 같은 기술 세부는 모른다.
>
> **패키지 분리 자체(§1의 3-패키지 구조)와 §3 Phase 로드맵·§4 테스트 전략은 유효하다.**
> 단 Phase 3의 개인화 추천은 진행하지 않고, 인기 검색어·검색 히스토리는 보류다.
> 상세: [2026-07-26 설계 변경](2026-07-26-search-recommendation-api-ownership-design.md)

## 1. 패키지 구조 — 기존 코드는 무변경, 형제 패키지 추가

현재 루트 `com.prompthub.product` 아래 계층(presentation/application/domain/infra)이
곧 product 도메인이다. 재배치하지 않고 형제 패키지 2개를 추가한다:

```
com.prompthub.product
├─ (기존 그대로) presentation / application / domain / infra / config   ← product 도메인
├─ search                          ← 기능명 채택 (기술명 "es" 대신 — recommendation과 결 맞춤)
│   ├─ presentation   SearchController: GET /api/v1/products(이관), GET /api/v1/search/suggest
│   ├─ application    검색·제안·색인 유스케이스, SearchPort(recommendation에 내주는 문)
│   └─ infra
│       ├─ es         클라이언트 설정, 인덱스 템플릿 관리, 쿼리 빌더
│       ├─ embedding  OpenAI 클라이언트(EmbeddingPort 구현), 자모/초성 변환기
│       ├─ messaging  컨슈머: product-events→색인, behavior-events→로그, order-events→구매로그
│       └─ batch      풀 리인덱스, 카운트 동기화(10분), 인기 검색어(1시간)
└─ recommendation
    ├─ presentation   GET /api/v1/recommendations/home, GET /api/v1/products/{id}/related(이관)
    ├─ application    비슷한상품·개인화 유스케이스, 다양성 규칙, 취향벡터 계산
    └─ infra          user-profiles 배치(6시간), 검색품질 일배치
```

- URL은 FE와의 계약이므로 유지하고, **컨트롤러 소유만 이관**한다:
  목록/검색(`GET /api/v1/products`) → search, related → recommendation.
  product 패키지는 상세·쓰기·검수 API만 남는다.

## 2. 의존 방향

```
recommendation → search → product      (역방향 전부 금지)
```

| 규칙 | 내용 |
|---|---|
| product는 아무도 모른다 | 검색·추천의 존재를 모른 채 Kafka 이벤트만 발행. product 코드에 ES·임베딩·추천 import가 생기면 경계 위반 |
| search → product | 읽기 전용 참조만 — 풀 리인덱스·색인 시 family 카운트 조회를 product의 조회 포트로. 이벤트 payload 클래스는 같은 코드베이스라 직접 공유(서비스 분리 시점에 복사 — 컨벤션 §5 방식 전환) |
| recommendation → search | ES 클라이언트 직접 사용 금지, search의 `SearchPort`(kNN·문서조회·색인 요청)만 사용. 추천 로직(가중치·다양성·사다리)은 recommendation 안에 |
| 의도적 예외 1건 | 상세 API(product)가 `?searchId=`를 받아 PRODUCT_VIEWED에 실어 발행 — 검색을 "아는" 게 아니라 유입 꼬리표 전달만 |

**나중에 recommendation을 서비스로 분리하면**: 잘리는 선이 이미 있다 —
SearchPort(→ HTTP/gRPC로 교체)와 Kafka 이벤트(이미 국경). A안(전부 이벤트 기반)의 보상.

## 3. Phase 로드맵

| Phase | 내용 | 완료 기준 |
|---|---|---|
| **1 — lexical 검색** | ES 컨테이너(nori 설치 커스텀 이미지, 로컬 compose), **v1 매핑에 벡터·자동완성 서브필드까지 전부 포함**(리인덱스 예방 — 차원·분석기가 이미 확정이므로), product-events에 `PRODUCT_ON_SALE` 추가, 색인 컨슈머, 풀 리인덱스·카운트 배치, 검색+정렬 API ES 전환(RDB 폴백 유지), search_after | `/browse`·검색이 ES로 동작, ES를 내려도 RDB 폴백으로 응답 |
| **2 — 시맨틱·자동완성·오타** | 색인 시 임베딩(+해시 스킵), 쿼리 임베딩+Redis 캐시+300ms 폴백, msearch+앱 RRF, 자모/초성 suggest API, Term Suggester 오타교정, related ES 전환 | 단어가 안 겹치는 "의도 검색"이 적중, 초성 자동완성 동작 |
| **3 — 행동로그·개인화** | behavior-events 발행+컨슈머, data stream+ILM, FE searchId 훅, 인기 검색어→suggest ②, Redis 최근검색(히스토리 API+suggest ③), user-profiles 배치, 개인화 홈 추천, 검색품질 일배치+어트리뷰션, Kibana(로컬) | 퍼널 대시보드에 검색→클릭→구매가 보임 |

각 Phase는 별도 구현 계획(`-plan.md`)으로 세분화한다 (writing-plans).

**스코프 제외**는 [0 — 개요](2026-07-16-es-0-overview-design.md#스코프-제외-추후-아이디어로만-기록) 참고.

## 4. 테스트 전략

- **순수 로직은 유닛으로** — 자모/초성 변환, RRF 병합, 다양성 규칙, 취향벡터 계산,
  임베딩 해시 스킵 판단. 이 설계에서 가장 틀리기 쉬운 부분들을 일부러 ES 없이
  테스트 가능한 순수 함수로 앱 쪽에 배치했다.
- **ES 통합 테스트는 Testcontainers** — nori 설치된 커스텀 이미지를 로컬 compose와
  테스트가 공유. 매핑 생성·색인·검색·자동완성·family 문서 교체 시나리오 검증.
  기존 `.claude/rules/testing.md` 기준(컨트롤러·서비스 테스트) 그대로 적용.
- **OpenAI는 포트 뒤로** — `EmbeddingPort` 인터페이스, 테스트는 fake. CI에서 외부 API 호출 금지.
- **컨슈머 멱등성** — 같은 eventId 2회 소비 → 색인 1회(409 스킵) 검증.

## 5. 인프라 메모

- 로컬: docker-compose에 ES 9.x 단일 노드(heap 1GB 안팎), Kibana는 필요할 때만 기동.
- nori는 공식 플러그인이지만 기본 이미지에 없음 → `elasticsearch-plugin install
  analysis-nori` 한 줄짜리 커스텀 Dockerfile.
- EC2/k8s 배포는 미정 (결정 D2) — 앱은 ES URL 설정값만 참조하므로 코드 무변경.
