# Product Service API

**Base:** `http://localhost:xxxx/api/v2`

> 최종 프로젝트 전환에 따라 product-service 외부 API는 `/api/v2`로 서빙한다(#273).
> 게이트웨이는 경로를 rewrite하지 않으므로(ADR-0007) 각 서비스가 해당 버전 경로를 직접 서빙한다.
> 서비스 간 내부 통신은 REST(`/internal/**`)가 아니라 gRPC로 통일되어 있다(#413, #431) — 남은
> `/internal/**` REST 엔드포인트는 없다.

## 공통 사항

- 인증이 필요한 엔드포인트는 `Authorization: Bearer {accessToken}` 헤더 필요
- 토큰 검증은 API Gateway에서 수행. 각 서비스는 헤더(`X-User-Id`, `X-User-Role`)만 읽음

---

## 상품 (공개)

### GET /products — 상품 목록 조회

- UC: UC-PRODUCT-03
- 인증: 불필요
- ON_SALE 상태 상품만 노출

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| q | string | N | `""` | 제목/설명 검색 |
| productType | string | N | `"all"` | `all\|PROMPT\|NOTION\|PPT\|EXCEL` |
| sort | string | N | `"popular"` | `popular\|rating\|price-asc` |
| page | number | N | `0` | 0부터 시작하는 페이지 번호 |
| size | number | N | `20` | 페이지당 항목 수 |

#### 검색 방식 (하이브리드)

`sort=popular`(기본값)이고 검색어가 있으면 **글자 기반 검색과 의미 기반 검색을 동시에 돌려
순위를 병합**한다. 단어가 겹치지 않는 자연어 질의("발표자료 예쁘게 만들어주는 거")에도
결과를 주기 위한 것이다.

- 글자 기반 검색 대상은 **이름·태그·설명·모델**이다(가중치 이름 3 > 태그 2 > 설명 1.5 >
  모델 1). 모델은 최하 가중치라 "gpt" 검색 시 이름·태그에 GPT가 있는 상품이 모델만 GPT인
  상품보다 항상 앞선다(#699). 본문(`content`)은 검색하지 않는다 — 프롬프트 본문은
  "예: 일별 주식 지수 데이터 수집" 같은 placeholder 예시로 가득해, 본문을 매칭하면 상품과
  무관한 검색어가 예시 문구에 걸려 오탐이 된다(#689)
- 여러 단어 검색은 **2단어면 모두, 3단어 이상이면 75% 이상** 일치해야 한다
  (`minimum_should_match: 2<75%`) — 대부분의 상품명이 공유하는 "프롬프트" 같은 단어
  하나만 겹치는 무관 상품이 꼬리로 딸려오는 것을 막는다(#689). 한 단어 검색은 영향 없다
- 검색어 속 **유형 단어**(`프롬프트/prompt`·`노션/notion`·`엑셀/excel`·`피피티/ppt`)는 글자
  매칭 대신 **productType 필터로 해석**한다 — "주식 프롬프트"는 PROMPT 유형에서 "주식"을
  찾는 것과 같고, "주식프롬프트"처럼 붙여 쓴 접미사도 해석한다. 유형 단어만 치면 그 유형
  전체가 인기순으로 나온다. `productType` 파라미터로 유형을 이미 지정한 요청은 검색어를
  해석하지 않는다(#689)
- 두 결과를 RRF(등수 기반 병합)로 섞는다. 점수를 더하지 않는 이유는 BM25 점수에 상한이 없고
  코사인 유사도는 0~1이라 스케일이 맞지 않기 때문이다
- 병합 대상은 각 방식의 **상위 100건**이다. 그 범위를 넘어가는 페이지는 글자 기반 결과만 준다
- 의미 기반 결과에는 **코사인 유사도 하한**이 걸린다. 하한이 없으면 kNN이 관련도와 무관하게
  상위 k건을 채워 돌려주므로, 색인 문서가 k보다 적을 때 어떤 질의에도 전체 상품이 나오고
  **검색 결과 0건이 발생할 수 없다**(#645)
- `meta.total`은 **글자 기반 전체 건수와 병합 결과 크기 중 큰 값**이다. 실제로 내려주는
  결과 수보다 작지 않다
- `sort=rating`·`price-asc`는 값 기준 정렬이라 순서가 그 필드로 결정되므로 하이브리드를
  적용하지 않는다
- 검색어 임베딩 생성이 300ms를 넘기면 그 요청은 **글자 기반 결과만으로 응답**한다.
  응답 형식은 동일하며 호출자가 구분할 필요가 없다

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "title": "사진 같은 제품 목업 생성기",
      "productType": "PROMPT",
      "model": "Midjourney v6",
      "amount": 5900,
      "rating": 4.9,
      "salesCount": 1240,
      "sellerId": "uuid",
      "desc": "상품 설명",
      "thumbnail_url": null,
      "tags": ["이미지생성", "목업"],
      "createdAt": "2026-05-01T00:00:00.000Z",
      "updatedAt": "2026-06-01T00:00:00.000Z"
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 12,
    "hasNext": false
  }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 상품 ID |
| title | string | 상품명 |
| productType | string | 상품 유형 (`PROMPT` \| `NOTION` \| `PPT` \| `EXCEL`) |
| model | string | 대상 AI 모델 |
| amount | integer | 현재 가격 |
| rating | number | 평균 별점 |
| salesCount | integer | 누적 판매 수 |
| sellerId | string | 판매자 ID |
| desc | string | 상품 설명 |
| thumbnail_url | string \| null | 썸네일 이미지 URL |
| tags | string[] | 판매자 지정 태그 목록 |
| createdAt | string | 생성일시 (ISO 8601) |
| updatedAt | string | 수정일시 (ISO 8601) |
| meta.page | integer | 현재 페이지 번호(0-base) |
| meta.size | integer | 페이지당 항목 수 |
| meta.total | integer | 전체 항목 수 |
| meta.hasNext | boolean | 다음 페이지 존재 여부 |

> `seller`(판매자 이름) 필드는 더 이상 내려주지 않는다(#440) — 프론트가 `sellerId`로
> user-service 배치 조회 API를 직접 호출해 렌더링한다.

---

### GET /products/suggest — 상품명 자동완성

- 인증: 불필요
- 용도: 검색창 타이핑 중 상품명 제안

**Query Parameter**

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| q | string | `""` | 검색어. 공백만 있거나 비어 있으면 조회하지 않고 빈 목록 반환 |

**Response** `200 OK`

```json
{
  "success": true,
  "data": ["시니어 코드리뷰 프롬프트", "코드 리팩터링 프롬프트"],
  "message": "success"
}
```

- 최대 5건, `salesCount` 내림차순
- 상품명 **중간 단어의 앞부분**으로도 매칭된다. `q=코드`가 `"시니어 코드리뷰 프롬프트"`를
  찾는다 — 이 서비스 상품명은 앞에 수식어가 붙는 형태라 첫 글자 prefix 매칭만으로는
  쓸모가 없다
- Elasticsearch 전용이다. ES 조회가 실패하면 **에러가 아니라 빈 목록**(`data: []`)을
  반환한다. RDB에 대응하는 조회가 없고, 자동완성은 드롭다운이 안 뜰 뿐 검색 자체를 막지
  않는다

> **현재 이 엔드포인트를 호출하는 클라이언트가 없다.** FE 자동완성은 제안할 어휘가 부족해
> 되돌렸다(FE#25 — 태그 11개, 상품명 단어 ~15개뿐이고 상위 태그는 눌러도 거의 전체가 나옴).
> 검색 자동완성의 재료는 검색 로그이므로 #381이 쌓인 뒤 #382와 함께 재검토한다.
> 엔드포인트는 되살리기 쉽게 남겨둔다.

---

### POST /products/wishlists — 찜 상품 배치 상세 조회

- 인증: 불필요
- 용도: 찜 목록 등 productId 목록만 갖고 있는 화면에서 카드 표시 정보를 한 번에 조회

#### Request

**Body**

```json
{ "productIds": ["uuid1", "uuid2"] }
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| productIds | string(UUID)[] | Y | 조회할 상품 ID 목록 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "productId": "uuid",
      "sellerId": "uuid",
      "title": "사진 같은 제품 목업 생성기",
      "amount": 5900,
      "thumbnailUrl": null,
      "productType": "PROMPT",
      "model": "Midjourney v6",
      "salesCount": 1240,
      "averageRating": 4.9,
      "status": "ON_SALE"
    }
  ],
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| productId | string | 상품 ID |
| sellerId | string | 판매자 ID |
| title | string | 상품명 |
| amount | integer | 현재 가격 |
| thumbnailUrl | string \| null | 썸네일 이미지 URL |
| productType | string | 상품 유형 |
| model | string | 대상 AI 모델 |
| salesCount | integer | 누적 판매 수 |
| averageRating | number | 평균 별점 |
| status | string | 상품 상태 |

요청한 productId 중 존재하지 않거나 현재 판매 중인 버전이 없는 상품은 응답 배열에서 제외된다.

---

### POST /products/orders — 구매 상품 배치 상세 조회

- 인증: 불필요
- 용도: 마이페이지 "구매한 프롬프트" 목록 등 주문에서 얻은 productId 목록으로 카드 표시 정보를 한 번에 조회. 응답의 `sellerId`로 user-service `POST /users/order-products`를 이어서 호출해 판매자 이름을 채운다.
- `POST /products/wishlists`와 요청/응답 계약이 동일하다(내부적으로 같은 조회를 재사용).

#### Request

**Body**

```json
{ "productIds": ["uuid1", "uuid2"] }
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| productIds | string(UUID)[] | Y | 조회할 상품 ID 목록 |

#### Response

**200 OK** — `POST /products/wishlists`와 동일한 item 구조(`productId`, `sellerId`, `title`, `amount`, `thumbnailUrl`, `productType`, `model`, `salesCount`, `averageRating`, `status`)

요청한 productId 중 존재하지 않거나 현재 판매 중인 버전이 없는 상품은 응답 배열에서 제외된다.

---

### GET /products/{productId}/orders — 구매 상품 reader 조회

- 인증: 필요 (Gateway 주입 `X-User-Id`)
- 용도: 구매한 프롬프트 reader 페이지(FE `/reader/[id]`)가 상품 데이터·유형별 콘텐츠·평균/내 별점을 한 번에 조회. 응답의 `sellerId`로 user-service `POST /users/order-products`를 이어서 호출해 판매자 이름을 채운다.
- 구매 여부 검증은 현재 하지 않는다(#550 결정, 후속 이슈에서 order-service 연동 예정).

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "productId": "uuid",
    "title": "면접 답변 프롬프트",
    "productType": "PROMPT",
    "model": "GPT-4o",
    "content": "프롬프트 본문...",
    "fileUrl": null,
    "externalUrl": null,
    "thumbnailUrl": "https://cdn/thumb.png",
    "sellerId": "uuid",
    "averageRating": 4.5,
    "myRating": 5
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| productId | string(UUID) | 요청한 상품 ID |
| title | string | 상품명 |
| productType | string | PROMPT / NOTION / PPT / EXCEL |
| model | string \| null | 대상 모델 |
| content | string \| null | 프롬프트 본문 (PROMPT만) |
| fileUrl | string \| null | presigned 다운로드 URL (PPT·EXCEL만) |
| externalUrl | string \| null | 외부 노션 링크 (NOTION만) |
| thumbnailUrl | string \| null | 썸네일 URL |
| sellerId | string(UUID) | 판매자 ID |
| averageRating | number | family 평균 별점 |
| myRating | number \| null | 요청 유저의 별점 (없으면 null) |

**404 Not Found** — 존재하지 않거나 현재 판매 중인 버전이 없는 상품 (`P001`)

---

### GET /products/{productId} — 상품 상세 조회

- 인증: 불필요

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "title": "사진 같은 제품 목업 생성기",
    "productType": "PROMPT",
    "model": "Midjourney v6",
    "amount": 5900,
    "rating": 4.9,
    "salesCount": 1240,
    "sellerId": "uuid",
    "sellerProductCount": 12,
    "desc": "상품 설명",
    "thumbnail_url": null,
    "imageUrls": [],
    "content": "[상품명]\n\n전체 내용은 구매 후 확인...",
    "tags": ["이미지생성", "목업"],
    "versions": [
      { "ver": "v1.3", "date": "2026-06-01", "note": "조명 프리셋 3종 추가" },
      { "ver": "v1.2", "date": "2026-05-10", "note": "배경 제거 옵션 개선" }
    ],
    "hasContext": true,
    "hasObjective": true,
    "hasNuance": false,
    "hasTone": true,
    "hasExamples": false,
    "hasExecution": true,
    "hasRoleAssignment": false,
    "checklistRecorded": true,
    "createdAt": "2026-05-01T00:00:00.000Z",
    "updatedAt": "2026-06-01T00:00:00.000Z"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| hasContext | boolean | AI 검수 체크리스트: 맥락 명시 여부 |
| hasObjective | boolean | AI 검수 체크리스트: 목표 명시 여부 |
| hasNuance | boolean | AI 검수 체크리스트: 뉘앙스 명시 여부 |
| hasTone | boolean | AI 검수 체크리스트: 톤 명시 여부 |
| hasExamples | boolean | AI 검수 체크리스트: 예시 포함 여부 |
| hasExecution | boolean | AI 검수 체크리스트: 실행 지침 포함 여부 |
| hasRoleAssignment | boolean | AI 검수 체크리스트: 역할 부여 포함 여부 (#671) |
| checklistRecorded | boolean | 위 체크리스트 7개가 실제로 검수 이벤트로 기록됐는지 여부. `false`면 7개 값은 무시한다 — "검수 미달"이 아니라 "이 기능 배포 전에 승인/반려되어 기록이 없음"이라는 뜻이다. FE는 이 값이 `false`일 때 체크리스트 섹션 자체를 숨겨야 한다 (#671) |

> `seller`(판매자 이름)·`sellerProfileImageUrl` 필드는 더 이상 내려주지 않는다(#440) — 프론트가
> `sellerId`로 user-service 배치 조회 API를 직접 호출해 렌더링한다. `sellerProductCount`는
> product-service 자체 집계(로컬 DB 조회)라 그대로 유지한다.
>
> `imageUrls`(상품 등록 시 올린 소개 이미지 목록)는 `thumbnail_url`(대표 썸네일 1장)과는 별개
> 필드다. 캐러셀은 `thumbnail_url` + `imageUrls`를 순서대로 이어붙여 보여주면 된다. 개수 제한은
> 백엔드에 별도 검증(예: `@Size`)이 없다 — 등록 폼에서 몇 장까지 받을지는 FE 업로드 UI 정책의
> 문제이고, 이 API는 저장된 값을 그대로 반환할 뿐이다.
>
> `thumbnail_url`/`imageUrls`는 저장 시 S3 key로 보관되고, 조회 응답 시점에
> `ObjectStorageGateway.createPresignedGetUrl(key)`로 presigned GET URL로 변환해 반환한다 — 공개
> 목록/상세/관련상품, 판매자 본인 목록, 찜 배치조회(`POST /products/wishlists`) 전부 동일 패턴이다.

---

### GET /products/{productId}/recommends — 추천 상품 조회

- 인증: 불필요
- 판매 중(ON_SALE) 상품 배열 반환
- **임베딩이 가까운 순으로 고른다.** 기준 상품의 다른 버전은 제외되고, 후보도 가족(버전 묶음)당 1건만 온다 — 같은 상품이 두 번 나오지 않는다(#699)
- 같은 `productType`이 소폭 가산점을 받지만 하드 필터가 아니다 — 내용이 정말 비슷하면 다른 유형도 올라온다

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| limit | number | N | `4` | 반환할 상품 수 |

#### Response

**200 OK** — 상품 목록 조회와 동일한 item 구조

---

### GET /products/{productId}/reviews — 별점 목록 조회

- 인증: 불필요

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "userId": "uuid",
      "rating": 5,
      "content": "리뷰 내용",
      "createdAt": "2024-01-01T00:00:00",
      "updatedAt": "2024-01-01T00:00:00"
    }
  ],
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 리뷰 ID |
| userId | string | 작성자 ID |
| rating | integer | 별점 (1~5) |
| content | string \| null | 리뷰 본문 |
| createdAt | string | 생성일시 |
| updatedAt | string | 수정일시 |

---

## 상품 (판매자)

### POST /products/uploads/presigned-urls — 업로드 URL 발급 (presigned PUT)

- 인증: 필요
- 필요 역할: SELLER
- 이미지·산출물 파일 업로드는 백엔드를 경유하지 않는다. 백엔드는 임시 object key와 presigned
  PUT/GET URL만 발급하고, 프론트가 PUT URL로 S3에 파일을 직접 올린다. 상품 생성/수정 요청과
  temp 취소 요청에는 URL이 아니라 **object key**를 그대로 넣어 보낸다 — presigned URL은
  만료가 있고 백엔드에서 재파싱하지 않는다.

#### Request

**Headers**

| 헤더 | 설명 |
|------|------|
| X-User-Id | 판매자 ID (API Gateway 주입) |
| X-User-Role | 사용자 역할 (API Gateway 주입) |

**Body**

```json
{ "purpose": "file", "fileName": "sample.pptx", "productType": "PPT" }
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| purpose | string | Y | `thumbnail` \| `image` \| `file` |
| fileName | string | Y | 원본 파일명(확장자 추출용) |
| productType | string | 조건부 | `purpose=file`일 때 필수(`PPT` \| `EXCEL`) |

- 확장자 검증(엄격): PPT→`pptx`/`ppt`, EXCEL→`xlsx`/`xls`, 이미지→`jpg`/`jpeg`/`png`/`gif`/`webp`.
  확장자가 없거나 맞지 않으면 400 `P008`. content-type은 발급 시 서명에 포함된다.

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "tempObjectKey": "products/temp/<sellerId>/file/<uuid>.pptx",
    "presignedPutUrl": "https://<presigned-put-url>",
    "presignedGetUrl": "https://<presigned-get-url>"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| tempObjectKey | string | 상품 생성/수정 요청과 temp 취소 요청에 그대로 넣을 값. 판매자 소유권이 key에 포함된다 |
| presignedPutUrl | string | 프론트가 파일 바이트를 직접 PUT할 대상(만료 있음) |
| presignedGetUrl | string | 업로드 완료 후 미리보기 표시에만 쓰는 만료 있는 GET URL(저장 요청에는 넣지 않는다) |

---

### DELETE /products/images — 임시 업로드 이미지/파일 정리

- 인증: 필요
- 필요 역할: SELLER
- 용도: 상품 등록/수정 중 이탈 시, 아직 상품에 연결되지 않은 temp 업로드 파일을 정리
- productId를 받지 않는다 — 요청 body는 presigned URL이 아니라 **object key 목록**이다.
  `products/temp/{sellerId}/...` 형식과 요청자 소유권을 검증해, 본인 소유의 temp key만
  삭제한다(영구 key나 다른 판매자의 key는 조용히 무시).

#### Request

**Headers**

| 헤더 | 설명 |
|------|------|
| X-User-Id | 판매자 ID (API Gateway 주입) |
| X-User-Role | 사용자 역할 (API Gateway 주입) |

**Body**

```json
["products/temp/<sellerId>/thumbnail/<uuid>.jpg"]
```

삭제할 temp object key 목록(`POST /products/uploads/presigned-urls` 응답의 `tempObjectKey`).

#### Response

**200 OK** — 응답 바디 없음

---

### POST /products — 상품 등록

- UC: UC-PRODUCT-01
- 인증: 필요
- 필요 역할: SELLER
- 등록 시 status: DRAFT

#### Request

**Headers**

| 헤더 | 설명 |
|------|------|
| X-User-Id | 판매자 ID (API Gateway 주입) |
| X-User-Role | 사용자 역할 (API Gateway 주입) |

**Body**

```json
{
  "title": "새 프롬프트 제목",
  "productType": "PROMPT",
  "model": "Claude 3.5",
  "desc": "설명",
  "amount": 5000,
  "content": "실제 프롬프트 내용",
  "thumbnailObjectKey": "products/temp/<sellerId>/thumbnail/<uuid>.png",
  "tags": ["태그1", "태그2"]
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| title | string | Y | 상품명 |
| productType | string | N | 상품 유형 (`PROMPT` \| `NOTION` \| `PPT` \| `EXCEL`, 기본값 `PROMPT`) |
| model | string | N | 대상 AI 모델 (PROMPT 타입일 때만 필수, 그 외 타입은 null) |
| desc | string | Y | 상품 설명 |
| amount | integer | Y | 가격 |
| content | string | 유형별 | 프롬프트 원문 (PROMPT 필수) |
| fileObjectKey | string | 유형별 | 산출물 파일 object key (PPT/EXCEL 필수, 업로드 후 받은 `tempObjectKey`) |
| externalUrl | string | 유형별 | 외부 노션 링크 (NOTION 필수) |
| thumbnailObjectKey | string | N | 썸네일 이미지 object key |
| imageObjectKeys | string[] | N | 소개 이미지 object key 목록 |
| tags | string[] | N | 판매자 지정 태그 목록 |

> **유형별 필수 필드**: PROMPT→`content`, PPT·EXCEL→`fileObjectKey`, NOTION→`externalUrl`. 각 유형은 해당 필드만 사용하며, 맞지 않는 필드가 채워지면 400 `P007`. 공개 상세 응답에는 `fileUrl`/`externalUrl`을 노출하지 않는다(구매 후 전달은 내부 API).
>
> `*ObjectKey` 필드는 `POST /products/uploads/presigned-urls` 응답의 `tempObjectKey`를 그대로
> 받는다. presigned URL이 아니라 key이며, 등록 시 상품 영구 경로로 이동(승격)된다. 요청자
> 소유가 아닌 temp key는 403 `P003`으로 거절한다.

#### Response

**201 Created**

```json
{
  "success": true,
  "data": {
    "productId": "uuid",
    "sellerId": "uuid",
    "title": "새 프롬프트 제목",
    "productType": "PROMPT",
    "model": "Claude 3.5",
    "desc": "설명",
    "amount": 5000,
    "status": "DRAFT",
    "createdAt": "2024-01-01T00:00:00"
  },
  "message": "success"
}
```

---

### PATCH /products/{productId} — 상품 수정

- UC: UC-PRODUCT-02
- 인증: 필요
- 필요 역할: SELLER
- 본인 상품만 수정 가능
- 버전 유형은 클라이언트가 고르지 않는다. 바뀐 필드로 BE가 판정한다.
  - 핵심 산출물(PROMPT `content` / NOTION `externalUrl` / PPT·EXCEL `fileUrl`) 변경, 또는
    FREE ↔ PAID 전환 → **MAJOR**(majorVersion 증가, 상태 → `PENDING_REVIEW`)
  - 그 외 필드(제목·설명·모델·가격·썸네일·이미지·태그)만 변경 → **PATCH**(patchVersion 증가,
    상태 `ON_SALE` 유지, 기존 ON_SALE row는 `SUPERSEDED`로 전환)
  - 아무것도 바뀌지 않으면 no-op — 새 row·파일 승격·이벤트 발행 없이 현재 상태를 그대로 반환한다
  - MAJOR·PATCH 모두 실제 변경이 있으면 `changeReason` 필수. no-op이면 안 보내도 된다
- DRAFT·REJECTED 상태 상품은 위 판정을 적용하지 않는다 — 같은 row·version에 내용만 덮어쓴다
  (새 row 생성·검수 요청 이벤트 없음). REJECTED는 재검수를 별도로
  `PATCH /products/{productId}/inspection`으로 요청해야 한다
- MAJOR로 갈 상품이 있는데 같은 family에 이미 `PENDING_REVIEW`가 있으면 409 `P006`

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Request

```json
{
  "title": "수정된 제목",
  "productType": "PROMPT",
  "model": "Claude 3.5",
  "desc": "수정된 설명",
  "amount": 6000,
  "content": "수정된 프롬프트 원문",
  "thumbnailObjectKey": "products/<productId>/thumbnail/<uuid>.png",
  "tags": ["태그1"],
  "changeReason": "내용 보강"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| title | string | Y | 상품명 |
| productType | string | N | 상품 유형 (`PROMPT` \| `NOTION` \| `PPT` \| `EXCEL`, 기본값 `PROMPT`) |
| model | string | N | 대상 AI 모델 (PROMPT 타입일 때만 필수, 그 외 타입은 null) |
| desc | string | Y | 상품 설명 |
| amount | integer | Y | 가격 |
| content | string | 유형별 | 프롬프트 원문 (PROMPT 필수) |
| fileObjectKey | string | 유형별 | 산출물 파일 object key (PPT/EXCEL 필수) |
| externalUrl | string | 유형별 | 외부 노션 링크 (NOTION 필수) |
| thumbnailObjectKey | string | N | 썸네일 이미지 object key. 새 파일이면 `tempObjectKey`, 안 바꿨으면 기존 영구 key를 그대로 보낸다 |
| imageObjectKeys | string[] | N | 소개 이미지 object key 목록 |
| tags | string[] | N | 판매자 지정 태그 목록 |
| changeReason | string | N | 변경 사유. 실제 변경이 있으면 필수(비어 있으면 400 `V001`) |

> **유형별 필수 필드**: PROMPT→`content`, PPT·EXCEL→`fileObjectKey`, NOTION→`externalUrl`. 각 유형은 해당 필드만 사용하며, 맞지 않는 필드가 채워지면 400 `P007`.
>
> `*ObjectKey` 필드는 temp key와 기존 영구 key를 함께 받는다. temp key(`products/temp/...`)는
> 이번 요청에서 상품 경로로 승격되고, 이미 영구 key(`products/{productId}/...`)면 그대로
> 유지된다(다시 이동하지 않음) — 수정 화면에서 이미지를 바꾸지 않아도 기존 값을 그대로 다시
> 보내면 된다.

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "productId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
    "version": "2.1",
    "status": "ON_SALE"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| productId | UUID | 이번 수정이 반영된 상품 ID(MAJOR면 새로 생성된 row, PATCH·no-op·DRAFT·REJECTED면 기존 row) |
| version | string | 반영 후 버전(`major.patch`) |
| status | string | 반영 후 상태 |

#### 에러

| 상태 | 코드 | 설명 |
|------|------|------|
| 400 | P007 | 상품 유형에 맞지 않는 필드 구성 |
| 400 | V001 | 실제 변경이 있는데 changeReason 누락 |
| 403 | P003 | 본인 상품이 아님 |
| 404 | P001 | 상품 없음 |
| 409 | P006 | 현재 상태에서 처리 불가(DRAFT·REJECTED·ON_SALE이 아니거나, MAJOR 전환인데 이미 PENDING_REVIEW 존재) |
| 409 | P009 | 동시 수정 충돌 — 같은 family·version으로 다른 요청이 먼저 반영됨. 최신 상품을 다시 불러와야 함 |

---

### DELETE /products/{productId} — 상품 삭제 / 판매 중단

- UC: UC-PRODUCT-04
- 인증: 필요
- 필요 역할: SELLER / ADMIN
- DRAFT·REJECTED 상태: 소프트 삭제 (deletedAt 설정, 목록 제외) — 둘 다 ON_SALE에 도달한 적이
  없어 판매 이력 보존 명분이 없다
- 그 외 상태: 판매 중단 (status → STOPPED, 목록 유지) — 판매 이력 보존

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Response

**200 OK** — 응답 바디 없음

---

### PATCH /products/{productId}/inspection — 검수 요청

- UC: UC-PRODUCT-06
- 인증: 필요
- 필요 역할: SELLER
- 상태 전이: DRAFT / REJECTED → PENDING_REVIEW
- DRAFT / REJECTED 외 상태에서 호출 시 409

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Response

**200 OK** — 응답 바디 없음

---

### GET /products/sellers/me — 판매자 본인 상품 목록

- UC: UC-PRODUCT-07
- 인증: 필요
- 필요 역할: SELLER

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "productId": "uuid",
      "title": "상품명",
      "productType": "PROMPT",
      "model": "Claude 3.5",
      "amount": 5000,
      "status": "DRAFT",
      "salesCount": 0,
      "averageRating": 0,
      "thumbnailUrl": null,
      "rejectionReason": null,
      "createdAt": "2024-01-01T00:00:00",
      "updatedAt": "2024-01-01T00:00:00"
    }
  ],
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| productId | string | 상품 ID |
| title | string | 상품명 |
| productType | string | 상품 유형 (`PROMPT` \| `NOTION` \| `PPT` \| `EXCEL`) |
| model | string | 대상 AI 모델 |
| amount | integer | 가격 |
| status | string | `DRAFT` \| `PENDING_REVIEW` \| `ON_SALE` \| `REJECTED` \| `STOPPED` |
| salesCount | integer | 누적 판매 수 |
| averageRating | number | family(버전군) 전체 리뷰 평균 별점. 리뷰 없으면 0 |
| thumbnailUrl | string \| null | 썸네일 이미지 URL |
| rejectionReason | string \| null | 반려 사유 (REJECTED 상태일 때) |
| createdAt | string | 생성일시 |
| updatedAt | string | 수정일시 |

---

### GET /products/sellers/me/summary — 판매자 본인 상품 요약(등록 상품 수·누적 판매 수)

- 인증: 필요
- 필요 역할: SELLER
- 판매자 대시보드 상단 요약 카드용. `productCount`는 family(버전군) 단위 등록 상품 수,
  `salesCount`는 판매자의 모든 상품을 통틀은 누적 판매 수다.

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "sellerId": "uuid",
    "productCount": 3,
    "salesCount": 42
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| sellerId | string | 판매자 ID |
| productCount | integer | 등록 상품 수 (family 단위) |
| salesCount | integer | 누적 판매 수 |

---

### GET /products/{productId}/sellers/me — 판매자 본인 상품 상세

- UC: UC-PRODUCT-08
- 인증: 필요
- 필요 역할: SELLER

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "productId": "uuid",
    "title": "상품명",
    "productType": "PROMPT",
    "model": "Claude 3.5",
    "amount": 5000,
    "desc": "설명",
    "content": "프롬프트 원문",
    "fileUrl": null,
    "fileObjectKey": null,
    "externalUrl": null,
    "status": "DRAFT",
    "version": "1.0",
    "averageRating": 0,
    "thumbnailUrl": null,
    "thumbnailObjectKey": null,
    "imageUrls": [],
    "imageObjectKeys": [],
    "tags": ["태그1", "태그2"],
    "liveVersion": "1.0",
    "versions": [
      {
        "version": "1.0",
        "status": "ON_SALE",
        "date": "2026-07-01",
        "changeReason": null,
        "rejectionReason": null,
        "hasContext": true,
        "hasObjective": true,
        "hasNuance": false,
        "hasTone": true,
        "hasExamples": false,
        "hasExecution": true,
        "hasRoleAssignment": false,
        "checklistRecorded": true
      }
    ]
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| fileUrl | string \| null | 산출물 파일 presigned 다운로드 URL (PPT/EXCEL, 미리보기용). 없으면 null |
| fileObjectKey | string \| null | 산출물 파일 object key(수정 요청용). 없으면 null |
| externalUrl | string \| null | 외부 노션 링크 (NOTION). 없으면 null |
| thumbnailObjectKey | string \| null | 썸네일 object key(수정 요청용). 없으면 null |
| imageObjectKeys | string[] | 소개 이미지 object key 목록(수정 요청용). `imageUrls`와 같은 순서 |
| averageRating | number | family(버전군) 전체 리뷰 평균 별점. 리뷰 없으면 0 |
| liveVersion | string \| null | 현재 판매중(ON_SALE) 버전 표기(`major.patch`). 판매중 버전이 없으면 null |
| versions | array | 이 상품의 버전 이력 목록 |
| versions[].version | string | 버전 표기(`major.patch`) |
| versions[].status | string | 해당 버전 상태 (`ON_SALE` / `SUPERSEDED` / `PENDING_REVIEW` / `REJECTED` 등) |
| versions[].date | string | 해당 버전 갱신일(YYYY-MM-DD) |
| versions[].changeReason | string \| null | 버전업 변경 사유 |
| versions[].rejectionReason | string \| null | 검수 반려 사유 (반려된 버전만) |
| versions[].hasContext | boolean | AI 검수 체크리스트: 맥락 명시 여부 |
| versions[].hasObjective | boolean | AI 검수 체크리스트: 목표 명시 여부 |
| versions[].hasNuance | boolean | AI 검수 체크리스트: 뉘앙스 명시 여부 |
| versions[].hasTone | boolean | AI 검수 체크리스트: 톤 명시 여부 |
| versions[].hasExamples | boolean | AI 검수 체크리스트: 예시 포함 여부 |
| versions[].hasExecution | boolean | AI 검수 체크리스트: 실행 지침 포함 여부 |
| versions[].hasRoleAssignment | boolean | AI 검수 체크리스트: 역할 부여 포함 여부 (#671) |
| versions[].checklistRecorded | boolean | 위 체크리스트 7개가 실제로 검수 이벤트로 기록됐는지 여부. `false`면 아직 검수 전(PENDING_REVIEW 최초 제출 등)이거나, 이 기능 배포 전에 이미 처리된 버전이라 기록이 없다는 뜻 (#671) |

---

## 상품 검수 (관리자)

관리자 상품 검수 API(목록 조회·승인·반려·되돌리기)는 admin-service로 이관되었다.
`docs/api-spec/admin.md` 참고.

---

## 리뷰

### POST /products/{productId}/reviews — 별점 작성

- 인증: 필요 (`X-User-Id` 헤더)
- 1상품 1리뷰 제약 — 이미 남긴 별점이 있으면 upsert(수정)로 처리
- 구매 여부는 서버에서 검증하지 않는다(알려진 한계, #440)

#### Request

**Body**

```json
{
  "rating": 5
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| rating | integer | Y | 1~5 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": null,
  "message": "success"
}
```

**400 Bad Request** — rating이 1~5 범위 밖 (`VALIDATION_FAILED`, V001)

---

## Kafka 이벤트

### 발행 (Producer)

#### `product-events`

`ProductEventProducer`가 발행한다. key는 `productId`(문자열).

| eventType | 발행 시점 | payload |
|---|---|---|
| `PRODUCT_REVIEW_REQUESTED` | 검수 제출(`submitForReview`) 또는 MAJOR 버전 수정으로 `PENDING_REVIEW`가 될 때, 오래 대기한 검수를 1회 재발행할 때 | 상품 스냅샷(`productId`, `productType`, `name`, `description`, `content`, `tags`, presign된 썸네일·이미지 URL, `duplicateOfProductId`, `free`) |

> 검색용으로 쓰이던 `PRODUCT_CHANGED`·`PRODUCT_STOPPED`·`PRODUCT_DELETED`·`PRODUCT_PRICE_CHANGED`는
> product-service가 20초 주기 scheduler로 RDB→ES를 직접 재조정하도록 전환하며 제거했다(PR5, I-2).
> ES는 이제 실시간 Kafka 이벤트가 아니라 이 scheduler-only 재조정으로만 갱신된다.

예시 (`PRODUCT_REVIEW_REQUESTED`):

```json
{
  "eventType": "PRODUCT_REVIEW_REQUESTED",
  "productId": "uuid",
  "productType": "PROMPT",
  "name": "제목",
  "description": "설명",
  "content": "본문",
  "tags": ["tag1"],
  "thumbnailUrl": "https://s3/presigned-thumb",
  "imageUrls": ["https://s3/presigned-1"],
  "duplicateOfProductId": null,
  "free": false,
  "occurredAt": "2026-08-12T12:00:00"
}
```

### 구독 (Consumer)

#### `order-events`

`OrderEventConsumer`가 구독한다(`group=product-service`, 수동 커밋).

| eventType | 처리 |
|---|---|
| `ORDER_PAID` | `payload.products[].productId` 목록의 판매량(salesCount) 증가 |
| `ORDER_REFUND` | 위 목록의 판매량 감소 |
| 그 외 | 무시(로그만 남김) |

예상 payload 구조:

```json
{
  "eventType": "ORDER_PAID",
  "payload": {
    "products": [
      { "productId": "uuid" }
    ]
  }
}
```

## gRPC

### 제공 (Server)

product-service가 서버로 구현해 다른 서비스에 노출하는 서비스다. 계약은 루트
`grpc/product/product_query.proto`의 단일 `ProductQueryService`로 관리한다(소유자=product).

#### `ProductQueryService` (소비: order-service, ai-service)

| rpc | 요청 | 응답 | 소비자 |
|---|---|---|---|
| `GetOrderSnapshots` | `product_ids[]` | `products[]`: `product_id`, `seller_id`, `title`, `product_type`, `amount`, `model` | order |
| `GetCartSnapshots` | `product_ids[]` | `products[]`: `product_id`, `seller_id`, `seller_nickname`, `title`, `product_type`, `amount`, `thumbnail_url` | order |
| `GetProductContent` | `product_id`, `product_ids[]`, `purpose` | `product_id`, `content`(구형), `results[]` | order |
| `GetSimilarProducts` | `seed_product_ids[]`, `limit_per_seed` | `rankings[]`: `seed_product_id`, `products[]`(`product_id`, `title`, `product_type`, `model`, `amount`, `rating`, `sales_count`, `seller_id`, `description`, `thumbnail_url`, `tags[]`) | ai |

`GetSimilarProducts`는 기준 상품마다 비슷한 상품 순위를 매겨 **합치지 않고 그대로** 돌려준다.
여러 기준의 순위를 어떤 가중치로 합칠지, 무엇을 빼고 몇 개를 보여줄지는 호출자(ai-service)가
정한다 — 여기서 합치면 "누구에게 무엇을 추천할지"라는 판단이 product-service로 넘어온다.
기준 상품이 판매 중이 아니거나 임베딩이 아직 없으면 그 기준의 `products`만 비어 돌아오고,
나머지 기준의 순위는 그대로 응답에 담긴다. 내부적으로는 기존 `GET /products/{id}/recommends`와
같은 pgvector 유사도 조회를 기준마다 재사용한다.

> `GetSellerStats`(셀러 통계)는 #452에서 user-service `sellersettlement` 소비자가 제거된 뒤,
> #483에서 공개 REST `GET /products/sellers/me/summary`로 전환하며 RPC 자체를 삭제했다.

`GetProductContent`는 주문 스냅샷·장바구니 스냅샷·구매 콘텐츠 조회를 하나의 진입점으로
통합하는 전환 1단계다(전체 설계:
`docs/superpowers/specs/2026-07-20-unified-get-product-content-design.md`). `purpose`
(`ProductContentPurpose`: `ORDER_SNAPSHOT` / `CART_SNAPSHOT` / `PURCHASED_CONTENT` /
구형 `UNSPECIFIED`)로 요청 목적을 구분하고, 응답 `results[]`(`oneof`: `order_snapshot` /
`cart_snapshot` / `purchased_content`)로 목적별 payload를 분리한다.

| purpose | `product_id` | `product_ids` | 응답 |
|---|---:|---:|---|
| `ORDER_SNAPSHOT` | 비어 있어야 함 | 1개 이상 | `results[]`가 전부 `order_snapshot` |
| `CART_SNAPSHOT` | 비어 있어야 함 | 1개 이상 | `results[]`가 전부 `cart_snapshot` |
| `PURCHASED_CONTENT` | 필수 | 비어 있어야 함 | `results`가 1건, `purchased_content` |
| 구형 `UNSPECIFIED` | 필수 | 비어 있어야 함 | 구형 `product_id`/`content` + `results`의 `purchased_content` |

그 외 조합, 빈 문자열, UUID 형식 오류는 `INVALID_ARGUMENT`. 단건 콘텐츠 대상 없음은
`NOT_FOUND`. `GetOrderSnapshots`/`GetCartSnapshots` RPC는 order-service 소비자 전환이
끝날 때까지 하위 호환을 위해 그대로 유지한다(전환 완료 후 별도로 제거 예정).

> `GetProductsByIds`(옛 user-service 소비용)는 실제 호출자가 없어 제거했다(#431) — 정확히는,
> user-service wishlist가 부르던 gRPC(`user.product.ProductService.GetProductsByIds`, user-service
> 로컬 proto)는 이 canonical RPC와 이름만 같을 뿐 완전히 다른 계약이었고, product-service는 그
> local 계약을 구현한 적이 없어 `UNIMPLEMENTED`로 실패했다(#447에서 발견). #478에서 공개 REST
> `POST /products/wishlists`를 추가했고, #485에서 User의 로컬 gRPC client를 제거한 뒤 Client가 이 REST를
> 직접 호출하도록 전환했다.

### 소비 (Client)

product-service는 현재 다른 서비스의 gRPC를 소비하지 않는다.

> 판매자 닉네임 조회용 `SellerQueryService`(`FindSellers`/`GetSeller`, 제공: user-service)를
> 호출하던 `SellerClient`/`GrpcSellerClientAdapter`와 product-service 쪽 로컬 계약 사본
> (`product-service/src/main/proto/seller_query.proto`)을 제거했다(#440) — 목록/상세/
> 관련상품 응답의 `seller`(이름) 필드가 없어지고, 장바구니 스냅샷의 `sellerNickname`도 빈
> 값으로 나간다(프론트가 직접 user-service 배치 조회로 채움). user-service 쪽 서버 구현
> (`ProductSellerQueryGrpcService`)과 그쪽 로컬 계약 사본은 product-service 담당이 아니라
> 손대지 않았다 — 호출자가 없어졌어도 정리 여부는 user-service 담당자가 판단한다.
