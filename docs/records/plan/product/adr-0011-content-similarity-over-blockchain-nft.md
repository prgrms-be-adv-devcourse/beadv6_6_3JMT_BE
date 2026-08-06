# ADR-0011: 상품 인증에 블록체인/NFT 대신 content 준-복제 탐지를 둔다 (PROMPT 전용)

- 상태: proposed → 구현 완료·머지됨 (블록체인 폐기·방향 확정. `feat/#597-ai-product-inspection`
  병합(PR #636). 정규화·`content_hash_at` 컬럼+트리거(V8)·중복 조회·payload/ai-service 연동까지
  `feat/#672-content-hash-dup-detection` 병합(PR #675)으로 develop 반영 — 2026-07-30 코드베이스
  재확인. **단, 복제 신호는 `submitForReview()` 검수요청 발행에만 얹혀 있다.** PENDING_REVIEW로
  가는 나머지 두 경로(`update(isMajor)`·`nextVersion(isMajor)`)는 애초에
  `PRODUCT_REVIEW_REQUESTED`를 발행하지 않아 복제 탐지도 안 탄다 — #597 자체의 기존 공백,
  아래 "통합 방식" 참고)
- 날짜: 2026-07-27
- 관련:
  - **AI 콘텐츠 정책 검수 루프** — 브랜치 `feat/#597-ai-product-inspection`(구현 완료):
    product-service `ProductReviewRequestedPayload` / `ProductEventProducer.publishReviewRequested`
    → `PRODUCT_REVIEW_REQUESTED`(product-events) → ai-service `ProductReviewRequestedConsumer`
    → `ProductInspectionService`(Spring AI OpenAI vision) → `InspectionVerdict{approved, rejectionReason}`
    → `PRODUCT_INSPECTION_COMPLETED`(ai-events) → product-service `ProductInspectionResultHandler`
    → `Product.approve()`(ON_SALE) / `Product.reject(reason)`(REJECTED). 설계:
    `product-service/docs/plans/ai-inspection/2026-07-27-ai-product-inspection-design.md`.
  - **이 ADR이 추가로 다루는 것**: 위 검수가 하지 못하는 **교차 상품 표절(준-복제) 탐지**.
  - 참고: 상품 임베딩 인프라(#378, pgvector)는 시맨틱 **검색**용이며, 이 기능은 그것을
    쓰지 않는다(아래 "왜 임베딩이 아니라 해시인가" 참고).
  - **구현 상태**: 해시 계산 골격(#632, `ProductContentHash.of()` / `Product.contentHash` /
    `V4__product_content_hash.sql`)에서 시작해, 정규화 v1·`content_hash_at`(V8)·중복 조회·
    ai-service 연동까지 #672(PR #675)로 완료됐다. **미완 1건**은 통합 진입점이
    `submitForReview()` 하나뿐인 것 — 아래 "메커니즘"·"통합 방식" 각 항목의 구현 상태 표시 참고.
  - **2026-07-29 로직 검증**: 최초 메커니즘(존재 여부만 보는 대칭 조회)이 동시 제출에서
    깨지는 문제, "먼저 왔음을 안다"와 "그걸 판정에 쓴다"의 불일치, "준-복제" 명명이 실제
    커버리지(정규화 완전일치)보다 넓게 잡힌 문제를 검토해 아래 메커니즘·비교 스코프·비대칭키
    섹션을 수정했다. 전부 `[미구현]` 상태에서의 계획 수정이라 기존 코드 변경은 없다.
  - **2026-07-29 4차 리뷰**: FM1 제거 증명이 `content_hash_at`의 전순서에 기대는데, 앱
    `LocalDateTime.now()` + 멀티 파드면 시계 스큐로 순서가 역전돼 FM1이 재발하는 문제를 짚어,
    **`content_hash_at`을 앱이 아니라 단일 DB 시계로 찍는다**고 못박았다(아래 "그래서 전용
    컬럼이 필요하다" 참고). 여전히 `[미구현]` 계획 수정이라 코드 변경은 없다.
  - **2026-07-30 구현 완료(PR #675, `feat/#672-content-hash-dup-detection`)**: 위 계획이 전부
    코드에 반영됐다 — `ProductContentHash.normalize()` v1(공백 축약 + trim + 소문자화),
    `V8__product_content_hash_at.sql`(컬럼 + `BEFORE INSERT OR UPDATE` 트리거로 DB 단일시계·
    조건부 갱신), `ProductRepository.findDuplicateOfProductId`(PROMPT·seller≠본인·status IN·
    `content_hash_at` 서브쿼리 비교), `ProductReviewRequestedPayload`/`ProductInspectionRequest`의
    `duplicateOfProductId`, ai-service `InspectionVerdict.rejectedAsDuplicate`(AI 호출 없이 이진
    자동 반려). 아래 본문의 `[미구현]`/`[미정]` 표시는 이 날짜로 해소된 것으로 읽는다.
    **미해소 1건**: 통합이 `submitForReview()`에만 걸려 §메커니즘 3번이 요구한 "3개 지점 모두"는
    1/3만 커버 — 나머지 두 경로가 검수요청 이벤트 자체를 안 내는 #597 기존 공백 때문(아래
    "통합 방식" 참고).

## 컨텍스트

"상품에 NFT/블록체인 인증을 붙이자"는 아이디어에서 출발해 실제로 막고 싶은 문제를 좁혔다.

1. 우리 상품은 실물 한정판이 아니라 복제 가능한 디지털 콘텐츠다. NFT/블록체인이 증명하는 건
   타임스탬프뿐이고, "이 텍스트가 저 텍스트와 얼마나 비슷한가"는 판단하지 못한다.
2. 막고 싶은 실제 대상은 **콘텐츠 무단 도용**이며, 사후 증거가 아니라 **등록 시점 사전 차단**을
   원한다. 원저작자가 억울해하는 전형적 상황은 "**띄어쓰기 하나만 바꿔** 남의 프롬프트를 새 상품
   으로 올리는 것" — 즉 **준-복제(near-verbatim)** 다. **(용어 범위, 2026-07-29 명확화)**
   이 문서에서 "준-복제"는 공백·대소문자·문장부호 차이만 지우는 **정규화 완전일치**가 커버하는
   범위로 한정한다. 단어 치환·어순 변경까지 잡는 일반적 의미의 near-verbatim은 범위 밖이다
   (아래 "왜 임베딩이 아니라 해시인가"의 `ponytail:` 메모, shingling 승격 참고).
3. `PENDING_REVIEW` 승인/반려는 #597에서 ai-service LLM 자동 검수로 대체됐다. 그러나 그 LLM
   검수는 상품 **하나만** 보고 정책 위반(불법·음란·사기·스팸·저품질)을 판단할 뿐, 다른 판매자의
   기존 상품과 대조하지 못해 **표절/복제는 구조적으로 못 잡는다.** → 이 ADR이 그 공백을 메운다.
4. 이 기능의 product-service ↔ ai-service 계약·구현은 담당자 위임으로 이 문서 작성자가 양쪽 다
   맡는다.

## 결정

- **블록체인/NFT는 폐기한다.**
- **범위는 PROMPT 유형 전용으로 한정한다(YAGNI).** 본문 텍스트(`content`)가 있는 유형은
  PROMPT뿐이다(PPT/EXCEL=파일, NOTION=외부링크 —
  `ProductContent.validateTypeFields`). 다른 유형의 복제는 파일 해시·URL 정규화 등 별도
  메커니즘이 필요하고 지금 필요가 확인되지 않았다. 구현상 kNN이 아니라 조회 조건에
  `product_type='PROMPT'`를 거는 것으로 처리한다.
- **메커니즘: `content` 정규화 해시 완전일치.** 임베딩/벡터/LLM을 쓰지 않는다.
  1. `content`를 정규화한다 — 공백 전체 축약(연속 공백·개행·탭 → 단일 규칙) + 앞뒤 trim +
     소문자화(필요 시 문장부호/마크다운 기호 제거까지). "띄어쓰기 하나" 수준의 차이는 여기서
     소거된다. **[구현됨, PR #675]** `ProductContentHash.normalize()`가 v1(연속 공백·개행·탭 →
     단일 스페이스 + 앞뒤 trim + 소문자화)으로 채워졌다. 문장부호·마크다운 제거는 오탐을 올려
     v1에서 뺐다(아래 트레이드오프). ASCII 공백만 정규화 대상이라 전각 공백(U+3000)은
     남긴다 — Java `normalize()`와 V8 백필 SQL이 같은 규칙을 타 결과가 일치한다.
  2. **[구현됨, #632]** 정규화 결과의 SHA-256 hex(64자)를 `product.content_hash` 컬럼
     (`V4__product_content_hash.sql`)에 저장하고 인덱싱한다. `applyContent()`에서 계산돼
     create·update(MAJOR)·nextVersion(MAJOR) 세 경로 모두 커버한다(당초 "submitForReview()
     시점" 계획보다 넓게 잡음 — 더 안전한 방향이라 그대로 채택).
  3. **[구현됨, PR #675 — 단 1/3 지점만]** 상품이 `PENDING_REVIEW`로 전이하는 시점
     (`submitForReview()` / `update(isMajor=true)` / `nextVersion(isMajor=true)` 세 곳 모두 —
     세 경로 다 `PENDING_REVIEW`로 보내므로 하나만 걸면 나머지 둘이 샌다. §2번 해시 계산이
     이미 세 경로를 다 덮는 것과 같은 이유)에 **같은 해시를 가진 다른 판매자의 PROMPT 상품 중
     이 상품보다 먼저 해시가 확정된 것이 있는지** 인덱스 조회한다. 있으면 복제로 본다(나중
     쪽만). 원래 안(단순 존재 조회)이 근접 동시 제출에서 깨지는 문제와 "먼저"의 기준은 아래
     "비교 스코프"에서 다룬다. 조회는 `ProductRepository.findDuplicateOfProductId`(구현체
     `ProductJpaRepository.findEarlierDuplicateProductIds`, `content_hash_at` 서브쿼리 비교·
     `ORDER BY content_hash_at ASC LIMIT 1`)가 한다. **단, 실제 배선은 `submitForReview()`
     한 곳뿐이다** — `update(isMajor)`·`nextVersion(isMajor)`는 나머지 두 전이 지점인데 이 둘은
     `publishReviewRequested`를 애초에 호출하지 않아(검수요청 이벤트 자체를 안 냄) 복제 조회가
     안 돈다. 여기서 경고한 "하나만 걸면 나머지 둘이 샌다"가 그대로 남았다(아래 "통합 방식").
- **왜 임베딩(#378)이 아니라 해시인가.**
  - 임베딩 코사인은 "**같은 뜻, 다른 표현**"(의역)을 잡는 도구다. 우리의 주 대상은 준-복제라
    임베딩은 과잉이면서 동시에 **오탐이 많다** — `name/tags/description`까지 합쳐 임베딩하는
    #378 벡터를 재사용하면 **같은 카테고리의 정직한 상품들끼리도 유사도가 올라가** reject
    오탐률이 커진다(카테고리 공용 어휘 때문). content만 따로 임베딩하려면 별도 벡터·컬럼·
    인덱스·배치를 새로 만들어야 해 "있는 것 재사용" 이점도 사라진다.
  - 해시는 정반대다: **content 본문에만** 반응하고, 본문이 실제로 다르면 충돌하지 않아 **오탐이
    사실상 0**이다 — 단, 이건 지금처럼 정규화가 스텁(원문 그대로)일 때 얘기다. **정규화를
    강화할수록(공백·대소문자를 넘어 문장부호·마크다운까지 지우면) 서로 다른 본문이 같은
    해시로 뭉칠 여지가 늘어 오탐률도 함께 오른다** — "오탐 0"과 "정규화 강화"(위 §1)는 같이
    극대화할 수 없는 트레이드오프다. 정규화 강도를 정할 때 이 트레이드오프를 실측 검증한다.
    OpenAI 호출이 없어 비용·지연이 없고, `#378`/`#586`/pgvector에 **의존하지 않는다.**
    완전일치는 이진 판단이라 임계값도 필요 없다.
  - **`content` 전문을 그대로 커버**한다 — #378 임베딩은 2000자에서 절단하지만
    (`EmbeddingSource.MAX_CONTENT_CHARS`), 해시는 전체 본문을 대상으로 하므로 긴 프롬프트도
    끝까지 본다.
  - `ponytail:` 정규화 해시는 정규화가 지우는 차이(공백·대소문자·문장부호)만 잡는다. **단어
    하나만 바꿔도 회피**된다. 소규모 편집까지 잡아야 할 필요가 실제로 확인되면 shingling(싱글링)
    (MinHash/LSH, Jaccard 임계값)으로 승격한다 — 그때는 임계값 판단이 생기므로 그 값은
    ai-service 쪽에 둔다. 지금은 만들지 않는다.
- **왜 비대칭키(전자서명/NFT)가 필요 없는가.** 강사 피드백: "NFT(=지금 하는 해싱)를 하려면
  비대칭키를 둬야 한다." 기술적으로는 맞는 말이지만 전제가 이 ADR과 다르다.
  - 비대칭키 서명(판매자 private key로 서명 → 누구나 public key로 검증)이 증명하는 건
    **"이 콘텐츠에 이 신원(seller)이 동의했다"** — 저작권 귀속·부인방지다. 반면 이 ADR의
    해시가 푸는 문제는 **"이 두 콘텐츠가 같은 글인가"** — 동일성 판정이다. 서로 다른 문제라
    서명을 붙여도 중복 탐지 능력이 늘지 않는다.
  - 서명은 "먼저 만든 사람"을 못 가른다. 표절자가 원본을 그대로 베껴 **자기** private key로
    서명해도 그 서명은 완벽하게 유효하다(자기 키로 자기가 서명하는 것뿐이라). "누가 먼저
    올렸나"는 결국 신뢰 가능한 순서 기록이 정한다 — 실제 NFT/블록체인에서 이 역할은 서명이
    아니라 **탈중앙 원장**이 한다.
  - 우리는 탈중앙이 아니다. product-service가 모든 등록을 처리하고, Gateway가 검증한 `sellerId`
    + **DB가 단일 시계로 찍는** `content_hash_at`(콘텐츠 해시 확정 시각 — 파드가 여럿이어도 값은
    한 DB 시계에서 나온다, 위 "비교 스코프"·4차 리뷰 참고)으로 "누가 먼저 이 내용을 냈나"를 이미
    위조 불가능하게 안다. 이 앎은 판정 로직에도 실제로 쓰인다 — `content_hash_at<본인` 조건이
    그것이다.
    서명·검증은 "중앙을 못 믿는 제3자가 스스로 검증"해야 하는 환경에서 의미가 있는데, 그런
    신뢰 공백이 우리 시스템엔 없다. 키 쌍을 얹어도 "먼저 온 판매자가 원본"이라는 판단은
    여전히 이 시각 비교가 한다 — 서명 계층은 그 위에 검증 가능한 도장 하나 얹는 것뿐이고,
    지금 필요한 판정엔 기능을 추가하지 않는다.
  - 결론: 비대칭키가 필요해지는 시점은 "우리 DB를 못 믿는 제3자에게 저작권을 증명해야 할 때"
    (분쟁 시 법적 증거, 탈중앙 마켓플레이스 등)이지, 지금처럼 우리 서버 내부에서 중복만
    걸러내는 단계가 아니다. 그 요구가 실제로 생기면 그건 이 ADR이 이미 폐기한 NFT/블록체인
    모델로 돌아가는 것이므로 별도 ADR로 재논의한다.
- **비교 스코프**: `product_type='PROMPT'` AND `seller_id<>본인` AND `status IN (ON_SALE,
  PENDING_REVIEW)` AND `content_hash_at<본인의 content_hash_at`(아래 참고). 같은 판매자
  제외로 `nextVersion()` 버전 계열의 자기-복제 오탐과 본인 상품 재제출(REJECTED→PENDING_REVIEW)
  자기매칭을 함께 막는다. 해시는 임베딩의 부분 인덱스(`WHERE status='ON_SALE'`) 제약이 없어
  PENDING_REVIEW 상태끼리도 비교 대상이다 — 단, **가시성 창(아래 "남는 잔여 위험" 참고)을
  벗어난 경우에만** 근접 제출 복제본을 잡는다. 그 창 안에서는 못 잡는다.
- **왜 순서 비교가 필요한가 (2026-07-29 리뷰로 최초 설계 수정, 이후 2차 리뷰로 해결 범위
  정정).** 최초 설계는 "같은 해시가 존재하는가"만 보는 **대칭** 조회였다. 근접한 시각에 서로
  다른 판매자가 같은 내용을 `PENDING_REVIEW`로 올리면 두 실패 모드가 있었다.
  - 두 조회가 서로의 커밋 **이후**에 실행되면 A도 B를 보고 B도 A를 봐서 **둘 다**
    `duplicateOfProductId != null`이 되어 원본까지 함께 반려된다(자기 붕괴 / **FM1**).
  - 두 조회가 서로의 커밋 **이전**에 실행되면 A도 B를 못 보고 B도 A를 못 봐서 **둘 다** 통과한다
    (TOCTOU 미탐 / **FM2**).
  **비대칭 비교(`content_hash_at<본인`)가 없애는 건 FM1뿐이다.** 진짜 먼저 확정된 쪽은 자기보다
  이른 로우를 찾을 수 없다 — 상대가 그 순간 보이든 안 보이든 상대의 `content_hash_at`은 항상
  자기보다 크므로, 확률이 아니라 조건식 자체로 절대 안 걸린다. **FM2는 이 비교로 안 없어진다.**
  값 비교를 아무리 정밀하게 해도 나중 조회 시점에 먼저 로우가 아직 커밋 전이면 그 로우 자체가
  조회 결과에 없다 — `content_hash_at`이 실제로 더 작아도 "안 보이면 못 건다." 이건 비교식이
  아니라 **가시성(visibility)**의 문제라 조건절을 바꿔도 안 풀린다.
  - **`created_at`은 못 쓴다.** `Product.update(isMajor=true)`/`nextVersion(isMajor=true)`는
    기존 로우의 `content`(따라서 해시)를 나중에 통째로 갈아치울 수 있는데 `createdAt`은 안
    바뀐다. 오래전에 만든 정상 상품을 나중에 도용 콘텐츠로 교체하면 `createdAt`이 옛날
    값이라 오히려 **도용한 쪽이 "원본"으로 판정**된다.
  - **`updatedAt`도 못 쓴다.** 조회수·판매수 증가(`incrementViewCount`/`incrementSalesCount`)
    등 콘텐츠와 무관한 변경도 `updatedAt`을 계속 앞으로 민다. 오래 팔린 원본(ON_SALE 상태로
    조회수가 쌓인)은 `updatedAt`이 계속 최신으로 밀리므로, 나중에 제출된 도용 상품보다
    `updatedAt`이 더 "늦어" 보여 비교가 무의미해진다. 하필 이 ADR이 보호하려는 대상(오래
    팔린 인기 프롬프트)이 정확히 이 함정에 걸린다.
  - **그래서 전용 컬럼이 필요하다.** `content_hash`와 함께 그 값이 확정된 시각만 담는
    `content_hash_at`(가칭) 컬럼을 추가한다. `applyContent()`가 실행될 때마다 무조건 갱신하면
    안 되고, **새로 계산한 해시가 기존 저장값과 실제로 다를 때만**(또는 최초 생성 시) 같이
    세팅한다 — 그 외 어떤 mutator도 안 건드린다. **[2026-07-29 3차 리뷰로 조건 추가]** 무조건
    갱신하면 원저작자의 무해한 재편집(정규화가 흡수하는 공백 수정 등, 정규화 후 해시값은
    그대로)마다 `content_hash_at`이 `now()`로 밀려 FM2 창에서 이미 슬쩍 통과해 있던 표절본보다
    자기 순위가 뒤로 밀린다 — 재제출 시 조회에서 그 표절본이 "나보다 이른" 것으로 잡혀 **원본이
    복제로 반려**된다. 하필 §"왜 순서 비교가 필요한가"가 보호하려는 대상(오래 팔린 인기
    프롬프트, 재편집 가능성이 높다)이 정확히 이 함정에 걸린다. 해시 값이 안 바뀌었으면
    `content_hash_at`도 그대로 둬야 이 경로가 막힌다. FM1(자기 붕괴)은 이 비교로 없앤다.
    구현 착수 시 `V4`에 이미 있는 `content_hash` 옆에 컬럼을 추가하는 마이그레이션이 하나 더
    필요하다. **[구현됨, PR #675] `V8__product_content_hash_at.sql`** — `content_hash_at` 컬럼
    추가 + `set_content_hash_at()` `BEFORE INSERT OR UPDATE` 트리거(아래 4차 리뷰의 SQL 그대로,
    해시가 실제로 바뀐 분기에서만 `now()`). V8은 normalize() v1 확정에 맞춰 기존 `content_hash`를
    새 규칙으로 재백필하고(트리거 생성 전에 실행해 재계산이 "변경"으로 안 잡히게 함),
    `content_hash_at`은 `created_at`으로 백필한다. 엔티티는 `content_hash_at`을
    `@Column(insertable=false, updatable=false)` 읽기 전용으로 매핑한다.
  - **[2026-07-29 4차 리뷰] `content_hash_at`은 앱이 아니라 단일 DB 시계가 찍는다.** 위
    비대칭 비교(`content_hash_at<본인`)의 FM1 제거 증명은 `content_hash_at`이 실제 제출 순서를
    반영하는 **전순서**라는 데 전적으로 기댄다. 그런데 product-service는 파드가 여럿이라(단일
    EC2 아님 — [[design-baseline-prod-failure-domains]]) 앱에서 `LocalDateTime.now()`로 찍으면
    파드 간 시계 스큐로 **나중 제출한 복제자가 더 이른 시각을 받을 수 있다.** 그러면 복제자가
    "원본"으로 판정되고 진짜 원본이 다음 재제출에서 복제로 반려된다 — 이 컬럼이 막으려던 바로
    그 FM1이 스큐로 재발하고, 저장값이라 한 번 굳으면 재제출마다 계속 잡히는 고착 오판이 된다.
    ADR-0002가 single-db라, **값을 DB 단일 시계로 찍으면** 모든 시각이 한 시계에서 나와 전순서가
    보장돼 이 역전이 사라진다. 이때 조건부 갱신(위: 해시가 실제로 바뀐 분기에서만)과 DB 시각
    스탬프는 `BEFORE INSERT OR UPDATE` 트리거 하나로 함께 처리한다 — 컬럼 `DEFAULT now()`나
    엔티티 필드로 두면 JPA 더티체크가 앱 값을 보내 안 먹는다(구체적 이유·트리거 예시는 아래
    "남은 판단거리"의 마이그레이션 항목). 엔티티에 `now()`를 박지 않아 `domain-model.md` §6과도
    부합한다.
    - **주의 1(구현):** 값이 DB에서 정해지므로 비대칭 비교 쿼리는 앱이 든 `LocalDateTime`이
      아니라 **방금 persist된 로우의 DB 값**을 참조해야 한다 — flush 후 비교하거나
      `WHERE content_hash_at < (SELECT content_hash_at FROM product WHERE id=:thisId)` 서브쿼리로.
    - **주의 2(범위):** 이 DB 스탬프는 **FM1만 닫는다.** FM2(아래 "남는 잔여 위험"의 가시성
      창)는 값 비교로 못 닫는 문제라 그대로 남는다 — DB `now()`가 FM2까지 없앤다고 오해하지 말
      것. 두 `content_hash_at`이 같은 μs로 동률이면 양쪽 다 통과해 **FM2로 degrade(안전)**할 뿐
      FM1로는 가지 않는다.
  - **남는 잔여 위험(FM2, TOCTOU 미탐) — 정정.** 이전 버전은 이 위험을 "같은 밀리초에 커밋되는
    완전 동시 확정"으로 적었는데 **틀렸다.** 실제 창은 값이 같아지는 순간이 아니라 **한쪽의
    커밋이 다른 쪽의 조회에 아직 안 보이는 구간 전체**다 — 두 `content_hash_at`이 수백 ms
    떨어져 있어도(밀리초 동률과 무관하게), 나중 제출의 조회가 먼저 제출의 커밋보다 먼저
    실행되면 그대로 둘 다 통과한다. 창의 크기는 트랜잭션 커밋 지연만큼이다(전형적으로
    수십~수백 ms, 부하·락 경합이 있으면 더 길어질 수 있다). 값 비교로는 못 닫는다 — 닫으려면
    **직렬화**가 필요하다(예: `content_hash`에 유니크 제약을 건 별도 "선점" 테이블에 먼저
    INSERT를 성공시키는 쪽만 원본으로 인정, 또는 커밋 후 재조회). **[2026-07-29 3차 리뷰]**
    이 예시를 그대로 구현하면 유니크 키가 `content_hash` 단독이라 §비교 스코프의
    `seller_id<>본인` 예외가 안 살아난다 — 본인이 major 버전을 올리거나 REJECTED 재제출을
    해도 두 번째 INSERT가 유니크 위반에 걸려 자기 자신에게 복제로 선점당한다. 실제로 만들
    때는 INSERT 충돌 시 기존 선점자의 `seller_id`를 확인해 본인이면 통과, 남이면 그 선점자를
    `duplicateOfProductId`로 쓰는 분기가 있어야 한다. `ponytail:` 이 직렬화 자체는 지금 안
    만든다 — 실제 동시 표절 제출이 관측되면 위 seller 예외를 포함해서 그때 추가한다. 미루는
    판단은 유지하되, 미루는 대상의 크기는 "밀리초 동률"이 아니라 **"동시 제출 표절이 둘 다
    통과하는 구간이 tx 지연만큼 존재한다"**로 정정한다.
- **통합 방식: 새 이벤트 없이 #597 흐름에 신호를 얹는다.** **[구현됨, PR #675 — 단 진입점 1/3]**
  아래 1~3은 모두 코드에 있으나, 1번의 조회가 실제로 걸린 전이 지점은 `submitForReview()`
  하나뿐이다. `update(isMajor)`·`nextVersion(isMajor)`는 `publishReviewRequested`를 안 내
  복제 조회를 안 탄다(§메커니즘 3번의 "3개 지점 모두"가 1/3만 충족). 나머지 두 경로에
  검수요청 발행을 다는 것은 #597(자동 검수)의 별도 공백이라 이 ADR 밖 후속 작업으로 남긴다.
  1. product-service가 `PENDING_REVIEW` 전이 3개 지점(§메커니즘 3번 —
     `submitForReview()`/`update(isMajor=true)`/`nextVersion(isMajor=true)`) 각각에서 위
     조회로 `duplicateOfProductId`(nullable UUID)를 구한다.
  2. 그 값을 `ProductReviewRequestedPayload`에 추가하고, 대응해서 ai-service
     `ProductInspectionRequest`도 확장한다(양쪽 다 작성자 소유).
  3. ai-service 검수 로직이 `duplicateOfProductId != null`이면 자동 반려 사유("기존 상품과
     동일한 본문")로 처리한다. 완전일치는 이진이라 임계값·가중치가 없어 판단이 단순하다.
  - 이점: (a) 가칭 `PRODUCT_SIMILARITY_CHECKED` 신규 이벤트 불필요. (b) 신호가 검수 이벤트
    하나에 합쳐져 흐르므로 "두 신호가 따로 도착해 판정 시점에 합류 못 하는 경쟁 조건"이 원천
    소멸. (c) 이벤트 유실 시 상품은 `PENDING_REVIEW`에 머물 뿐이라 검사를 건너뛴 채 조용히
    승인되는 fail-open 위험이 없다(#597의 멱등·상태가드 상속). (d) 모든 반려가 #597의 단일
    경로(`ProductInspectionResultHandler`)로 흐르므로 product-service에 별도 반려 배선이 안 든다.
  - **[확정, PR #675] 판정 주체 = ai-service.** `V4__product_content_hash.sql` 주석의
    "판정은 admin-service가 테이블 직접읽기로 한다"는 메모는 #632 작업자의 개인 메모였을 뿐,
    채택하지 않았다. 완전일치는 이진이라 사람 판단이 불필요하고, admin에 `contentHash` 매핑·
    타 서비스 테이블 직접읽기 결합을 새로 만들 이유가 없다. 위 1~3 그대로 product-service가
    `duplicateOfProductId`를 구해 #597 검수 이벤트에 실어 보내고, ai-service
    `ProductInspectionService`가 non-null이면 AI 호출 없이 `rejectedAsDuplicate`로 자동 반려한다.

## 결과

- **(2026-07-30 갱신) 구현 완료·머지됨(단, `submitForReview` 진입점 한정).**
  `feat/#597-ai-product-inspection`(PR #636)에 이어, 정규화·`content_hash_at`(V8)·중복 조회·
  payload/ai 연동까지 `feat/#672-content-hash-dup-detection`(PR #675)으로 develop에 반영됐다.
  **유일한 미완**: 복제 조회가 `submitForReview()`에만 걸려 major/next-version 재제출 경로는
  아직 안 탄다(위 "통합 방식").
- 계약 변경(`ProductReviewRequestedPayload` / `ProductInspectionRequest`에
  `duplicateOfProductId` 추가)은 작성자가 양쪽 다 구현해 단독 확정했다. **(완료, PR #675)**
- 새로 드는 비용: 검수 제출 1건당 해시 계산(무료) + 인덱스 조회 1회. OpenAI 추가 호출 0.
- 최소 검증 3개 **(전부 구현됨, PR #675)**:
  1. "공백/대소문자만 다른 두 content가 같은 정규화 해시를 갖는다"를 고정하는 단위 테스트
     (정규화 로직이 깨지면 실패). → `ProductContentHashTest.normalizesWhitespaceAndCase`
     (전각 공백 미정규화 케이스도 함께 고정).
  2. "같은 해시를 가진 두 상품 중 `content_hash_at`이 이른 쪽만 통과하고 늦은 쪽만
     `duplicateOfProductId`가 채워진다"를 고정하는 테스트(비교가 대칭으로 퇴행하는 걸 막는다).
     → `ProductDuplicateDetectionIntegrationTest`(크로스-셀러 탐지·자기매칭 제외·상태 필터·
     Java↔Postgres 정규화 일치).
  3. "정규화 후 해시값이 그대로인 `update()`(공백만 고친 재편집)는 `content_hash_at`을 안
     바꾼다"를 고정하는 테스트 — 위 "그래서 전용 컬럼이 필요하다"의 원저작자 순위 역전을 막는다.
     → V8 트리거의 `IS DISTINCT FROM` 분기가 이를 보장한다.
- 남은 판단거리(구현 스펙·확정 사항은
  `docs/records/plan/product/2026-07-29-content-hash-dup-detection-plan.md`에 정리):
  - 정규화 강도(문장부호·마크다운까지 제거할지) — 위 "왜 임베딩이 아니라 해시인가"의 오탐
    트레이드오프를 실측하며 정한다. shingling 승격 필요 여부(단어단위 편집 회피가 실제로
    관측되는지)도 함께.
    → **확정(2026-07-29): v1은 공백·대소문자만.** 문장부호·마크다운 제거는 오탐을 올려 v1에서
    빼고, 단어단위 편집 회피가 실측되면 정규화 강화가 아니라 바로 shingling으로 승격한다.
  - **해시 대조 판정 주체** — ai-service(#597 흐름, 위 "통합 방식" 1~3) vs admin-service
    (직접 테이블 조회, `V4` 마이그레이션 주석의 메모).
    → **확정(2026-07-29): ai-service.** 완전일치는 이진이라 사람 판단이 불필요하고, admin에
    `contentHash` 매핑·타 서비스 테이블 직접읽기 결합을 새로 만들 이유가 없다. `V4` 주석의
    admin 직접읽기 메모는 채택하지 않는다.
  - **[완료, PR #675] `content_hash_at` 컬럼 추가 마이그레이션 = `V8__product_content_hash_at.sql`**
    (`V4`의 `content_hash` 옆) — 위 "비교 스코프"·"그래서 전용 컬럼이 필요하다"(값은 앱이 아니라
    **DB 단일 시계**로 찍는다) 참고. 순서 비교를 `created_at`/`updatedAt` 재사용으로 때우지
    않았다(이유는 해당 섹션). 아래 트리거 방식·SQL을 그대로 채택.
    - **찍는 방법은 `BEFORE INSERT OR UPDATE` 트리거로 한다.** 컬럼 `DEFAULT now()`나 엔티티
      필드로 두면 안 된다 — `Product`는 `createdAt`/`updatedAt`/`contentHash`를 전부 앱에서
      찍어(엔티티 참고) `content_hash_at`만 DB 값이라 혼자 튀는데, 평범한 JPA 필드로 두면
      Hibernate 더티체크가 앱이 든 값(또는 null)으로 INSERT/UPDATE를 보내 DB `now()`가 안
      먹는다. 그러면 구현자가 엔티티에 `LocalDateTime.now()`를 박게 되고 4차 리뷰가 없앤 파드
      스큐 FM1이 그대로 재발한다. 트리거 하나가 (a) DB 단일 시계, (b) "해시가 실제로 바뀐
      분기에서만 갱신"(3차 리뷰 조건), (c) 앱을 스탬프에서 완전 배제 — 셋을 한곳에서 처리한다.

      ```sql
      IF NEW.content_hash IS DISTINCT FROM OLD.content_hash THEN
          NEW.content_hash_at := now();
      END IF;
      ```

      비교 쿼리는 이 트리거가 찍은 DB 값을 읽어야 하므로(앱 `LocalDateTime` 아님) flush 후
      비교하거나 서브쿼리로 참조한다 — 위 "그래서 전용 컬럼이 필요하다" §주의 1과 같다.
    **기존 로우 백필 시각**은 이 제약과 무관하다 — 신규 제출은 항상 `now()`라 마이그레이션
    시점보다 무조건 늦으므로, 백필값은 마이그레이션 이전의 아무 과거 값(마이그레이션 실행
    시각·`created_at` 등)이어도 앞으로의 보장은 안 깨진다. 유일한 사각지대는 이 기능 도입
    **이전에** 이미 콘텐츠가 스왑 도용된 기존 로우끼리의 상대 순서인데, 오늘도 그런 도용을
    잡을 방법이 없었던 기존 데이터 문제라 이 기능의 범위 밖으로 둔다.
  - **FM2(TOCTOU, 동시 제출 미탐) 직렬화 여부** — 위 "왜 순서 비교가 필요한가"의 마지막
    항목대로 지금은 값 비교만으로 두고 직렬화는 안 만든다. 동시 표절 제출이 실제로 관측되면
    선점 테이블(유니크 제약) 또는 커밋 후 재조회로 승격한다.
- `product-service/CLAUDE.md`의 "쓰기 API 미구현" 서술은 이미 낡았다. 정정은 이 ADR 범위 밖.
