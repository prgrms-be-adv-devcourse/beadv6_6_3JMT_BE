# Product API 규칙

## 공개 조회 API

아래 API는 인증이 필요 없다.

- `GET /api/v1/products`
- `GET /api/v1/products/{productId}`
- `GET /api/v1/products/{productId}/related`
- `GET /api/v1/products/{productId}/reviews`

API 명세에서 다르게 정의하지 않는 한, 공개 상품 목록/상세/관련 상품 조회는 `ON_SALE` 상태 상품만 노출한다.

## 인증 필요 API

Product 쓰기/리뷰/관리자 API는 JWT를 직접 파싱하지 않는다.

프론트는 Gateway로 아래 헤더를 보낸다.

```http
Authorization: Bearer {accessToken}
```

API Gateway는 토큰을 검증한 뒤 각 서비스 요청에 아래 헤더를 주입한다.

```http
X-User-Id: {userUuid}
X-User-Role: BUYER | SELLER | ADMIN
```

Product Service는 인증 필요 API에서 Gateway가 주입한 헤더만 읽는다.

## Category 계약

프론트와 API는 category 값을 code로 주고받는다.

허용 category code:

- `image`
- `writing`
- `coding`
- `marketing`
- `chatbot`
- `data`

권장 DB 구조:

```text
category.code = API 요청/응답 및 필터링 값
category.name = 화면 표시명
```

예시:

```text
code=writing, name=글쓰기
code=coding, name=코딩
```

DB category 테이블은 `code`와 `name` 두 컬럼을 모두 가진다.

| 컬럼 | 역할 | 예시 |
|------|------|------|
| `code` | 필터 query param 매칭값 | `image`, `coding` |
| `name` | 화면 표시명 | `이미지 생성`, `코딩` |
| `icon` | FE ICON_MAP key (Lucide 아이콘 slug) | `pen-line`, `code-xml` |

**필터링**: 프론트가 `?category=coding`으로 보내면 `c.code = :category`로 매칭한다.

**API 응답**: `category` 필드는 `c.name`(표시명), `icon` 필드는 `c.code`(코드)를 반환한다.

```json
{
  "category": "코딩",
  "icon": "coding"
}
```

## ID 계약

ID 타입은 API 명세와 현재 DDL을 확인한 뒤 구현한다.

- docs/ERD는 UUID 기준일 수 있다.
- 기존 local table은 임시 numeric ID를 사용할 수 있다.
- numeric ID와 UUID를 조용히 섞지 않는다.
- 이슈 단위로 기준을 정하고 PR에 결정 내용을 남긴다.

## 응답 형식

프로젝트 공통 응답 형식을 따른다.

```json
{
  "success": true,
  "data": {},
  "message": "success"
}
```

목록/페이지 응답은 `docs/api-spec/product.md`를 따른다.

## DDL과 docs 일치 확인

`docs/erd/schema.md`는 실제 DDL의 열람용 미러이므로 최신 상태가 아닐 수 있다.

엔티티/컬럼과 관련된 구현을 시작하기 전에 아래를 확인한다.

- 실제 DB DDL(`init-script/postgres/schema.sql` 또는 DB 직접 조회)과 `schema.md`를 대조한다.
- 컬럼이 추가·변경·삭제됐는데 `schema.md`에 반영되지 않은 경우 `schema.md`를 먼저 수정한다.
- 도메인 모델(`@Entity`)이 실제 DDL과 다른 경우 엔티티도 함께 수정한다.
- docs와 실제 DB가 다르면 임의로 결정하지 않고 사용자에게 확인받는다.

## 프론트 호환성

Product API는 프론트 프로젝트 `C:\programmers_prj\beadv6_6_3JMT_FE`의 해당 메뉴 흐름과 호환되어야 한다.

공개 조회 응답은 최소한 아래 프론트 Product 화면 흐름과 맞아야 한다.

- `/browse`
- `/detail/[id]`
- home 상품 섹션

확인할 항목:

- 프론트가 호출하는 URL path
- 프론트가 넘기는 query param
- 프론트가 기대하는 response field
- 상품 ID 타입과 route param 처리 방식
- DDL 기준 실제 column/entity 구조

docs, DDL, 프론트 구현 중 하나라도 서로 맞지 않으면 임의로 결정하지 않는다.
특히 URL path, query param, ID 타입, response field가 다르면 사용자에게 확인받고 넘어간다.

docs와 프론트 mock data의 필드명이 다르면 DDL과 API docs를 먼저 확인한다.
그 뒤에도 어느 쪽으로 맞출지 불명확하면 사용자에게 확인받는다.
