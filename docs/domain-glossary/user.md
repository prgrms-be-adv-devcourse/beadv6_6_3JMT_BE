# User Service 도메인 용어 사전

---

## 사용자 (user)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | user_id | UUID | ✓ | | PK |
| 이름 * | name | VARCHAR(100) | ✓ | | 사용자 이름 |
| 이메일 * | email | VARCHAR(255) | ✓ | | |
| 프로필 이미지 | profile_image_url | VARCHAR(500) | | NULL | 프로필 이미지 URL |
| 상태 * | status | user_status_type | ✓ | ACTIVE | ACTIVE / BLOCKED / WITHDRAWN |
| 약관 동의 * | terms_agreed | BOOLEAN | ✓ | FALSE | 서비스 이용약관 동의 여부 |
| 역할 * | role | user_role_type | ✓ | | USER / SELLER / ADMIN |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |

---

## 인증 연동 (auth)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | auth_id | UUID | ✓ | gen_random_uuid() | PK |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.user_id |
| 제공자 * | provider | auth_provider_type | ✓ | | KAKAO / NAVER / GOOGLE |
| 제공자 사용자 ID * | provider_user_id | VARCHAR(100) | ✓ | | 소셜 플랫폼 고유 ID. UNIQUE(provider, provider_user_id) |
| 연동 일시 * | connected_at | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |

---

## 판매자 (seller)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 판매자 식별자 * | seller_id | UUID | ✓ | gen_random_uuid() | PK |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.user_id |
| 상호명 * | seller_name | VARCHAR(100) | ✓ | | 상호명 또는 판매자 노출 이름 |
| 사업자등록번호 | business_number | VARCHAR(50) | | NULL | 개인/해외 판매자 확장성을 위해 NULL 허용 |
| 상태 * | status | seller_status_type | ✓ | PENDING | PENDING / ACTIVE / SUSPENDED |
| 승인 일시 | approved_at | TIMESTAMPTZ | | NULL | 미승인 시 NULL |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | 판매자 신청/등록 일시 |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |

---

## 찜 (wishlist)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | wishlist_id | UUID | ✓ | gen_random_uuid() | PK |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.user_id |
| 상품 ID * | product_id | UUID | ✓ | | FK → product.product_id |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | CURRENT_TIMESTAMP | |
