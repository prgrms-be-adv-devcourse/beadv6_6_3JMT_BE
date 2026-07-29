# Product Service 도메인 용어 사전

---

## 상품 (product)

버전 표기: `major_version.patch_version` (예: 1.0, 1.3, 2.0)

`SUPERSEDED`: 새 버전이 `ON_SALE`로 전환되며 밀려난 이전 `ON_SALE` 행 (버전 계열 내 노출 상품은 항상 1개)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | id | UUID | ✓ | gen_random_uuid() | PK |
| 부모 상품 ID | parent_id | UUID | | NULL | 버전 계열(family) 루트 상품 ID로 self-reference(DB FK 제약 없음). NULL이면 자신이 루트(최초 등록 버전). 항상 루트를 직접 가리킴(중간 버전 체인 아님) |
| 판매자 ID * | seller_id | UUID | ✓ | | FK → seller.seller_id |
| 메이저 버전 * | major_version | SMALLINT | ✓ | 1 | MAJOR 선택 시 +1, patch_version 0 리셋 |
| 패치 버전 * | patch_version | SMALLINT | ✓ | 0 | PATCH 선택 시 +1 |
| 변경 사유 | change_reason | VARCHAR(500) | | NULL | 버전업 시 작성. 최초 등록은 NULL |
| 상품명 * | name | VARCHAR(200) | ✓ | | |
| 상품 설명 * | description | TEXT | ✓ | | |
| 상품 유형 * | product_type | VARCHAR(50) | ✓ | | PROMPT / NOTION / PPT / EXCEL |
| 대상 모델 | model | VARCHAR(100) | | NULL | 대상 AI 모델명 |
| 가격 유형 * | amount_type | VARCHAR(255) | ✓ | PAID | FREE / PAID (CHECK constraint) |
| 가격 * | amount | INT | ✓ | 0 | |
| 대표 이미지 URL | thumbnail_url | TEXT | | NULL | NULL이면 기본 이미지 사용 |
| 이미지 URL 목록 | image_urls | TEXT | | NULL | 다중 이미지 URL(쉼표 구분 문자열, TagsConverter 사용). 별도 테이블 없이 이 컬럼으로 관리 |
| 원문 콘텐츠 | content | TEXT | | NULL | 프롬프트/템플릿 원문(PROMPT 전용). **외부 API 응답 노출 금지** |
| 파일 URL | file_url | TEXT | | NULL | PPT/EXCEL 등 파일형 상품의 다운로드 URL(PPT/EXCEL 전용) |
| 외부 링크 URL | external_url | TEXT | | NULL | NOTION 등 외부 링크형 상품의 URL(NOTION 전용) |
| 본문 해시 | content_hash | VARCHAR(64) | | NULL | 복제 탐지용 본문 해시(PROMPT 전용, ADR-0011) |
| 뱃지 | badge | VARCHAR(50) | | NULL | 상품 뱃지 (`신규` 등) |
| 상품 상태 * | status | VARCHAR(255) | ✓ | DRAFT | DRAFT / PENDING_REVIEW / ON_SALE / REJECTED / STOPPED / SUPERSEDED (CHECK constraint) |
| 반려 사유 | rejection_reason | VARCHAR(1000) | | NULL | REJECTED 상태에서만 유효 |
| 누적 판매 수 * | sales_count | INT | ✓ | 0 | |
| 조회 수 * | view_count | INT | ✓ | 0 | |
| 찜 수 * | wish_count | INT | ✓ | 0 | |
| 태그 | tags | VARCHAR(255) | | NULL | 판매자 지정 태그 (쉼표 구분 문자열, TagsConverter 사용) |
| 생성 일시 * | created_at | TIMESTAMP | ✓ | | |
| 수정 일시 * | updated_at | TIMESTAMP | ✓ | | |
| 삭제 일시 | deleted_at | TIMESTAMP | | NULL | 소프트 삭제 일시 |

---

## 리뷰 (review)

1상품 1리뷰: DB 제약이 아니라 애플리케이션 레벨에서 보장한다(`user_id` + `product_id`로 기존 리뷰를 찾아 upsert). `rating` 1~5 범위도 DB CHECK가 아니라 요청 DTO의 Bean Validation(`@Min(1) @Max(5)`)으로 검증한다.

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | review_id | UUID | ✓ | gen_random_uuid() | PK |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.user_id |
| 상품 ID | product_id | UUID | | NULL | FK → product.product_id |
| 별점 * | rating | SMALLINT | ✓ | | 1~5 (애플리케이션 레벨 검증, DB CHECK 없음) |
| 내용 | content | VARCHAR(255) | | NULL | 리뷰 본문 |
| 상태 * | status | VARCHAR(255) | ✓ | | ACTIVE / HIDDEN (관리자 숨김, CHECK constraint) |
| 생성 일시 * | created_at | TIMESTAMP | ✓ | | |
| 수정 일시 * | updated_at | TIMESTAMP | ✓ | | |
| 삭제 일시 | deleted_at | TIMESTAMP | | NULL | 소프트 삭제 일시 |
