# Product Service 도메인 용어 사전

---

## 카테고리 (category)

자기 참조 구조 (`parent_id`). 최상위 카테고리는 `parent_id = NULL`.

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | id | UUID | ✓ | gen_random_uuid() | PK |
| 부모 카테고리 ID | parent_id | UUID | | NULL | FK → category.category_id (자기 참조) |
| 코드 * | code | VARCHAR(50) | ✓ | | API 필터/응답 식별값 (`image`, `writing` 등). UNIQUE |
| 이름 * | name | VARCHAR(100) | ✓ | | 화면 표시명 (`이미지 생성`, `글쓰기` 등) |
| 아이콘 | icon | VARCHAR(50) | | NULL | FE ICON_MAP key (`pen-line`, `code-xml` 등) |
| 노출 순서 * | display_order | INT | ✓ | 0 | |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | | |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | | |

---

## 상품 (product)

버전 표기: `major_version.patch_version` (예: 1.0, 1.3, 2.0)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | id | UUID | ✓ | gen_random_uuid() | PK |
| 판매자 ID * | seller_id | UUID | ✓ | | FK → seller.seller_id |
| 카테고리 ID | category_id | UUID | | NULL | FK → category.category_id |
| 메이저 버전 * | major_version | SMALLINT | ✓ | 1 | MAJOR 선택 시 +1, patch_version 0 리셋 |
| 패치 버전 * | patch_version | SMALLINT | ✓ | 0 | PATCH 선택 시 +1 |
| 변경 사유 | change_reason | VARCHAR(500) | | NULL | 버전업 시 작성. 최초 등록은 NULL |
| 상품명 * | name | VARCHAR(200) | ✓ | | |
| 상품 설명 * | description | TEXT | ✓ | | |
| 상품 유형 * | product_type | VARCHAR(50) | ✓ | | PROMPT 등 |
| 가격 유형 * | amount_type | VARCHAR(20) | ✓ | PAID | FREE / PAID (CHECK constraint) |
| 가격 * | amount | INT | ✓ | 0 | |
| 대표 이미지 URL | thumbnail_url | VARCHAR(500) | | NULL | NULL이면 기본 이미지 사용 |
| 원문 콘텐츠 | content | TEXT | | NULL | 프롬프트/템플릿 원문. **외부 API 응답 노출 금지** |
| 뱃지 | badge | VARCHAR(50) | | NULL | 상품 뱃지 (`신규` 등) |
| 상품 상태 * | status | VARCHAR(30) | ✓ | DRAFT | DRAFT / PENDING_REVIEW / ON_SALE / REJECTED / STOPPED (CHECK constraint) |
| 반려 사유 | rejection_reason | VARCHAR(1000) | | NULL | REJECTED 상태에서만 유효 |
| 누적 판매 수 * | sales_count | INT | ✓ | 0 | |
| 조회 수 * | view_count | INT | ✓ | 0 | |
| 찜 수 * | wish_count | INT | ✓ | 0 | |
| 태그 | tags | TEXT | | NULL | 판매자 지정 태그 (쉼표 구분 문자열, TagsConverter 사용) |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | | |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | | |
| 삭제 일시 | deleted_at | TIMESTAMPTZ | | NULL | 소프트 삭제 일시 |

---

## 상품 이미지 (product_image)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | image_id | UUID | ✓ | gen_random_uuid() | PK |
| 상품 ID * | product_id | UUID | ✓ | | FK → product.product_id. 상품 삭제 시 CASCADE |
| 이미지 URL * | image_url | VARCHAR(500) | ✓ | | |
| 노출 순서 * | sort_order | INT | ✓ | 0 | |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | | |

---

## 리뷰 (review)

1상품 1리뷰 제약: `UNIQUE(product_id, user_id)`, `CHECK(rating BETWEEN 1 AND 5)`

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | review_id | UUID | ✓ | gen_random_uuid() | PK |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.user_id |
| 상품 ID | product_id | UUID | | NULL | FK → product.product_id |
| 별점 * | rating | SMALLINT | ✓ | | 1~5 |
| 내용 | content | TEXT | | NULL | 리뷰 본문 |
| 상태 * | status | review_status_type | ✓ | | ACTIVE / HIDDEN (관리자 숨김) |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | | |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | | |
| 삭제 일시 | deleted_at | TIMESTAMPTZ | | NULL | 소프트 삭제 일시 |
