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
      "originalAmount": null,
      "rating": 4.9,
      "salesCount": 1240,
      "sellerId": "uuid",
      "badge": "신규",
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
| originalAmount | integer \| null | 할인 전 원래 가격 (할인 없으면 null) |
| rating | number | 평균 별점 |
| salesCount | integer | 누적 판매 수 |
| sellerId | string | 판매자 ID |
| badge | string | 뱃지 (`신규` 등) |
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
    "badge": "신규",
    "desc": "상품 설명",
    "thumbnail_url": null,
    "imageUrls": [],
    "content": "[상품명]\n\n전체 내용은 구매 후 확인...",
    "tags": ["이미지생성", "목업"],
    "versions": [
      { "ver": "v1.3", "date": "2026-06-01", "note": "조명 프리셋 3종 추가" },
      { "ver": "v1.2", "date": "2026-05-10", "note": "배경 제거 옵션 개선" }
    ],
    "features": ["고해상도 출력 지원", "상업적 이용 가능", "버전 업데이트 무료 제공"],
    "createdAt": "2026-05-01T00:00:00.000Z",
    "updatedAt": "2026-06-01T00:00:00.000Z"
  },
  "message": "success"
}
```

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
> `storageClient.generatePresignedDownloadUrl(key)`로 presigned GET URL로 변환해 반환한다 — 공개
> 목록/상세/관련상품, 판매자 본인 목록, 찜 배치조회(`POST /products/wishlists`) 전부 동일 패턴이다.

---

### GET /products/{productId}/recommends — 연관 상품 조회

- 인증: 불필요
- 동일 productType 상품 배열 반환

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
- 이미지·산출물 파일 업로드는 백엔드를 경유하지 않는다. 백엔드는 presigned PUT URL만 발급하고,
  프론트가 그 URL로 S3에 파일을 직접 PUT한 뒤, 반환된 `fileUrl`을 상품 생성/수정 요청의
  `thumbnailUrl` / `imageUrls` / `fileUrl`에 넣어 보낸다.

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
  맞지 않으면 400 `P008`. content-type은 발급 시 서명에 포함된다.

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "uploadUrl": "https://<presigned-put-url>",
    "fileUrl": "https://<bucket>.s3.<region>.amazonaws.com/products/temp/file/<uuid>.pptx?..."
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| uploadUrl | string | 프론트가 파일을 직접 PUT할 대상(만료 있음) |
| fileUrl | string | 업로드 후 상품 생성/수정 요청에 넣을 값(임시 경로). 생성/수정 시 상품 경로로 이동됨 |

---

### DELETE /products/images — 임시 업로드 이미지/파일 정리

- 인증: 필요
- 필요 역할: SELLER
- 용도: 상품 등록/수정 중 이탈 시, 아직 상품에 연결되지 않은 temp 업로드 파일을 정리
- productId를 받지 않는다 — 요청 body의 URL 문자열에서 직접 S3 key를 파싱해 삭제하며,
  `products/temp/`로 시작하는 key만 삭제 대상이다(그 외는 조용히 무시).

#### Request

**Headers**

| 헤더 | 설명 |
|------|------|
| X-User-Id | 판매자 ID (API Gateway 주입) |
| X-User-Role | 사용자 역할 (API Gateway 주입) |

**Body**

```json
["https://<bucket>.s3.<region>.amazonaws.com/products/temp/thumbnail/<uuid>.jpg?..."]
```

삭제할 파일들의 presigned URL(또는 원본 key) 목록.

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
  "thumbnailUrl": "https://...",
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
| fileUrl | string | 유형별 | 산출물 파일 URL (PPT/EXCEL 필수, 업로드 후 받은 URL) |
| externalUrl | string | 유형별 | 외부 노션 링크 (NOTION 필수) |
| thumbnailUrl | string | N | 썸네일 이미지 URL |
| tags | string[] | N | 판매자 지정 태그 목록 |

> **유형별 필수 필드**: PROMPT→`content`, PPT·EXCEL→`fileUrl`, NOTION→`externalUrl`. 각 유형은 해당 필드만 사용하며, 맞지 않는 필드가 채워지면 400 `P007`. 공개 상세 응답에는 `fileUrl`/`externalUrl`을 노출하지 않는다(구매 후 전달은 내부 API).

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
- MINOR 수정: patchVersion 증가, 상태 유지
- MAJOR 수정: majorVersion 증가, 상태 → PENDING_REVIEW

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
  "thumbnailUrl": "https://...",
  "tags": ["태그1"],
  "changeReason": "내용 보강",
  "versionType": "MINOR"
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
| fileUrl | string | 유형별 | 산출물 파일 URL (PPT/EXCEL 필수, 업로드 후 받은 URL) |
| externalUrl | string | 유형별 | 외부 노션 링크 (NOTION 필수) |
| thumbnailUrl | string | N | 썸네일 이미지 URL |
| tags | string[] | N | 판매자 지정 태그 목록 |
| changeReason | string | N | 변경 사유 |
| versionType | string | N | `MINOR`(기본) \| `MAJOR` |

> **유형별 필수 필드**: PROMPT→`content`, PPT·EXCEL→`fileUrl`, NOTION→`externalUrl`. 각 유형은 해당 필드만 사용하며, 맞지 않는 필드가 채워지면 400 `P007`.

#### Response

**200 OK** — 응답 바디 없음

---

### DELETE /products/{productId} — 상품 삭제 / 판매 중단

- UC: UC-PRODUCT-04
- 인증: 필요
- 필요 역할: SELLER / ADMIN
- DRAFT 상태: 소프트 삭제 (deletedAt 설정, 목록 제외)
- 그 외 상태: 판매 중단 (status → STOPPED, 목록 유지)

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
    "externalUrl": null,
    "status": "DRAFT",
    "version": "1.0",
    "averageRating": 0,
    "thumbnailUrl": null,
    "tags": ["태그1", "태그2"],
    "liveVersion": "1.0",
    "versions": [
      {
        "version": "1.0",
        "status": "ON_SALE",
        "date": "2026-07-01",
        "changeReason": null,
        "rejectionReason": null
      }
    ]
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| fileUrl | string \| null | 산출물 파일 presigned 다운로드 URL (PPT/EXCEL). 없으면 null |
| externalUrl | string \| null | 외부 노션 링크 (NOTION). 없으면 null |
| averageRating | number | family(버전군) 전체 리뷰 평균 별점. 리뷰 없으면 0 |
| liveVersion | string \| null | 현재 판매중(ON_SALE) 버전 표기(`major.patch`). 판매중 버전이 없으면 null |
| versions | array | 이 상품의 버전 이력 목록 |
| versions[].version | string | 버전 표기(`major.patch`) |
| versions[].status | string | 해당 버전 상태 (`ON_SALE` / `SUPERSEDED` / `PENDING_REVIEW` / `REJECTED` 등) |
| versions[].date | string | 해당 버전 갱신일(YYYY-MM-DD) |
| versions[].changeReason | string \| null | 버전업 변경 사유 |
| versions[].rejectionReason | string \| null | 검수 반려 사유 (반려된 버전만) |

---

## 상품 검수 (관리자)

### GET /admin/products — 전체 상품 목록 조회

- UC: UC-PRODUCT-05
- 인증: 필요
- 필요 역할: ADMIN

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "productId": "uuid",
      "title": "상품명",
      "sellerNickname": "김철수",
      "productType": "PROMPT",
      "model": "Claude 3.5",
      "amount": 5000,
      "status": "PENDING_REVIEW",
      "createdAt": "2024-01-01T00:00:00"
    }
  ],
  "message": "success"
}
```

---

### PUT /admin/products/{productId}/approve — 검수 승인

- UC: UC-PRODUCT-05
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: PENDING_REVIEW → ON_SALE

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Response

**200 OK** — 응답 바디 없음

---

### PUT /admin/products/{productId}/reject — 검수 반려

- UC: UC-PRODUCT-05
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: PENDING_REVIEW → REJECTED

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Request

```json
{
  "reason": "반려 사유"
}
```

#### Response

**200 OK** — 응답 바디 없음

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
| `PRODUCT_DELETED` | 상품 삭제 시 | `productId`, `occurredAt` |
| `PRODUCT_PRICE_CHANGED` | 상품 가격 변경 시 | `productId`, `previousPrice`, `changedPrice`, `occurredAt` |
| `PRODUCT_STOPPED` | 상품 판매 중지 시 | `productId`, `occurredAt` |

예시 (`PRODUCT_PRICE_CHANGED`):

```json
{
  "eventType": "PRODUCT_PRICE_CHANGED",
  "productId": "uuid",
  "previousPrice": 10000,
  "changedPrice": 8000,
  "occurredAt": "2026-07-07T12:00:00"
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

#### `ProductQueryService` (소비: order-service)

| rpc | 요청 | 응답 |
|---|---|---|
| `GetOrderSnapshots` | `product_ids[]` | `products[]`: `product_id`, `seller_id`, `title`, `product_type`, `amount`, `model` |
| `GetCartSnapshots` | `product_ids[]` | `products[]`: `product_id`, `seller_id`, `seller_nickname`, `title`, `product_type`, `amount`, `thumbnail_url` |
| `GetProductContent` | `product_id`, `product_ids[]`, `purpose` | `product_id`, `content`(구형), `results[]` |

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
