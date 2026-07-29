# User Service 도메인 용어 사전

> 이 문서의 테이블 정의는 `user-service/src/main/resources/db/migration/V*.sql`과 각 `@Entity`
> 클래스(`domain/model`)를 기준으로 작성한다. **기본값** 열의 초기값은 대부분 DB `DEFAULT` 절이
> 아니라 정적 팩토리(`Xxx.create()` 등)가 애플리케이션에서 설정한다 — 마이그레이션에 실제
> `DEFAULT` 절이 있는 컬럼은 `refresh_token.epoch`(0)뿐이다. 시각 컬럼도 `auth.connected_at`만
> `TIMESTAMPTZ`(시간대 포함)이고 나머지는 전부 `TIMESTAMP`(시간대 없음)다.

---

## 사용자 (user)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | id (Java 필드명: `userId`) | UUID | ✓ | | PK |
| 이름 * | name | VARCHAR(100) | ✓ | | 사용자 이름 |
| 이메일 * | email | VARCHAR(255) | ✓ | | UNIQUE |
| 프로필 이미지 | profile_image_url | VARCHAR(500) | | NULL | 프로필 이미지 URL |
| 상태 * | status | VARCHAR(20) | ✓ | | ACTIVE / BLOCKED / WITHDRAWN (CHECK). 생성 시 `User.create()`가 ACTIVE로 고정 |
| 약관 동의 * | terms_agreed | BOOLEAN | ✓ | | 서비스 이용약관 동의 여부 |
| 생성 일시 * | created_at | TIMESTAMP | ✓ | | `BaseEntity`(`@CreatedDate`)가 관리 |
| 수정 일시 * | updated_at | TIMESTAMP | ✓ | | `BaseEntity`(`@LastModifiedDate`)가 관리 |

> **역할(role)은 `user` 테이블 컬럼이 아니다.** 사용자는 여러 역할을 가질 수 있어(`Set<UserRole>`)
> 별도 `user_role` 조인 테이블로 관리한다. 아래 섹션 참고.

---

## 사용자 역할 (user_role)

`User.roles`(`@ElementCollection`)가 매핑하는 다중 역할 조인 테이블이다. 사용자 한 명이 여러 역할을
동시에 가질 수 있다(예: SELLER 승인 후에도 BUYER 유지). API 응답의 대표 역할(`role`)은
`User.getPrimaryRole()`이 ADMIN > SELLER > BUYER 우선순위로 계산한다.

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 사용자 ID * | user_id | UUID | ✓ | | PK(복합, user_id+role) · FK → user.id |
| 역할 * | role | VARCHAR(20) | ✓ | | PK(복합) · BUYER / SELLER / ADMIN (CHECK) |

---

## 인증 연동 (auth)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | id (Java 필드명: `authId`) | UUID | ✓ | | PK |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.id |
| 제공자 * | provider | VARCHAR(20) | ✓ | | KAKAO / NAVER / GOOGLE (CHECK) |
| 제공자 사용자 ID * | oauth_id | VARCHAR(100) | ✓ | | 소셜 플랫폼 고유 ID. UNIQUE(provider, oauth_id) |
| 연동 일시 * | connected_at | TIMESTAMPTZ | ✓ | | `Auth.create()`가 `Instant.now()`로 설정 |

---

## 리프레시 토큰 (refresh_token)

로그인 세션(RT)을 저장하는 테이블이다. `epoch`는 RTR(Refresh Token Rotation)마다 증가하는 세션
버전이며, `GET /internal/authorize/{userId}`가 AT의 `epoch` 클레임과 대조해 무효화된 세션을
판별하는 데 쓰인다(`docs/api-spec/auth.md` 참고). `V2__add_refresh_token_epoch.sql`에서
`epoch` 컬럼이 추가됐다(V1 baseline 작성 시 엔티티에는 있었지만 마이그레이션에서 누락됐던 컬럼).

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | id (Java 필드명: `refreshTokenId`) | UUID | ✓ | | PK |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.id |
| 토큰 * | token | TEXT | ✓ | | JWT 리프레시 토큰 원문 |
| 세션 버전(epoch) * | epoch | BIGINT | ✓ | 0 | RTR마다 +1. `RefreshToken.rotate()`가 증가시킴 |
| 만료 일시 * | expires_at | TIMESTAMPTZ | ✓ | | |

---

## 판매자 등록 신청 (seller_register)

> **판매자 식별자**: 별도 `seller` 테이블을 두지 않는다. 서비스 간 판매자 식별자는 `user.id`로 통일한다.

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | id (Java 필드명: `sellerRegisterId`) | UUID | ✓ | | PK (신청 추적용, 판매자 식별자 아님) |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.id. **판매자 식별자로 사용** |
| 상태 * | status | VARCHAR(20) | ✓ | | PENDING / APPROVED / REJECTED (CHECK). 생성 시 `SellerRegister.create()`가 PENDING으로 고정 |
| 주력 카테고리 | categories | — | | | `seller_register_category` 조인 테이블로 관리(1:N). 아래 섹션 참고 |
| 소개 | introduction | TEXT | | NULL | 판매할 프롬프트 소개 |
| 포트폴리오 URL | portfolio_url | VARCHAR(500) | | NULL | 블로그/포트폴리오/SNS 링크 |
| 약관 동의 * | agreed_to_terms | BOOLEAN | ✓ | | 판매자 이용약관 및 정산 정책 동의 여부 |
| 신청 일시 * | submitted_at | TIMESTAMP | ✓ | | `SellerRegister.create()`가 `LocalDateTime.now()`로 설정 |
| 심사 완료 일시 | reviewed_at | TIMESTAMP | | NULL | 심사 전 NULL |
| 반려 사유 | reject_reason | TEXT | | NULL | 반려된 경우에만 값 존재 |

---

## 판매자 등록 카테고리 (seller_register_category)

`SellerRegister.categories`(`@ElementCollection`)가 매핑하는 다중 값 조인 테이블이다.

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 신청 ID * | seller_register_id | UUID | ✓ | | FK → seller_register.id |
| 카테고리 * | category | VARCHAR(100) | ✓ | | 주력 카테고리 값(예: `marketing`). 최대 개수(3개) 등은 애플리케이션에서 검증 |

> 별도 PK 제약은 없다(Hibernate `@ElementCollection` 기본 매핑).

---

## 찜 (wishlist)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | id (Java 필드명: `wishlistId`) | UUID | ✓ | | PK |
| 사용자 ID * | user_id | UUID | ✓ | | FK → user.id |
| 상품 ID * | product_id | UUID | ✓ | | FK → product.product_id (타 서비스 소유, DB 레벨 제약 아님) |
| 생성 일시 * | created_at | TIMESTAMP | ✓ | | Hibernate `@CreationTimestamp`가 INSERT 시 자동 설정 |

---

## 판매자 정산 (seller_settlement)

settlement-service가 계산한 정산 결과를 user-service 쪽 조회용 read model로 저장하는 테이블이다.
사용자가 직접 생성하지 않고 `SellerSettlement.seed()`(V1)/`seedV1()`/`seedV2()`(V2 payload)로
동기화된다. `V3__reset_seller_settlement_for_analysis.sql`에서 V2 read model 전환을 위해
기존 데이터를 전량 삭제(`DELETE FROM seller_settlement`, 파괴적 one-time reset)하고
`payload_version` 컬럼을 추가했다 — 사용자·인증·판매자 등록 등 다른 데이터는 영향받지 않는다.

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | seller_settlement_id | UUID | ✓ | | PK |
| 정산 ID * | settlement_id | UUID | ✓ | | settlement-service 원본 정산 ID. UNIQUE |
| 판매자 ID * | seller_id | UUID | ✓ | | = user.id |
| 정산 시작일 * | period_start | DATE | ✓ | | |
| 정산 종료일 * | period_end | DATE | ✓ | | |
| 상품 건수 * | product_count | INT | ✓ | | |
| 총 판매 금액 * | total_amount | NUMERIC(12,2) | ✓ | | |
| 정산 총액 * | settlement_total_amount | NUMERIC(12,2) | ✓ | | |
| 수수료 총액 * | fee_total_amount | NUMERIC(12,2) | ✓ | | |
| 환불 금액 | refund_amount | NUMERIC(12,2) | | NULL | |
| 산정 일시 * | calculated_at | TIMESTAMP | ✓ | | |
| Payload 버전 * | payload_version | SMALLINT | ✓ | | 1 또는 2 (CHECK). V3에서 추가 — 상세 목록(details) 유무 구분 |
| 상태 * | status | VARCHAR(30) | ✓ | | WAITING / APPROVAL_ON_HOLD / APPROVED / PAYOUT_REQUESTED / PAYOUT_ON_HOLD / PAID / CANCELLED (CHECK). 생성 시 WAITING으로 고정 |
| 승인 일시 | approved_at | TIMESTAMP | | NULL | |
| 지급 신청 일시 | payout_requested_at | TIMESTAMP | | NULL | |
| 지급 완료 일시 | paid_at | TIMESTAMP | | NULL | |
| 취소 일시 | cancelled_at | TIMESTAMP | | NULL | |
| 생성 일시 * | created_at | TIMESTAMP | ✓ | | `BaseEntity`(`@CreatedDate`)가 관리 |
| 수정 일시 * | updated_at | TIMESTAMP | ✓ | | `BaseEntity`(`@LastModifiedDate`)가 관리 |

인덱스: `idx_seller_settlement_seller_period` ON (seller_id, period_start, period_end) — V3에서 추가.

---

## 판매자 정산 상세 (seller_settlement_detail)

`seller_settlement` 1건에 속한 라인(주문상품 단위 매출/환불) 내역이다. `V3`에서 신설됐고,
`seller_settlement_id`에 `ON DELETE CASCADE`로 연결된다. 생성 후 값이 바뀌지 않는 불변
레코드라 `updated_at`이 없다(`created_at`만 자체 필드로 보유, `BaseEntity` 미사용).

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | settlement_detail_id | UUID | ✓ | | PK |
| 정산 ID * | seller_settlement_id | UUID | ✓ | | FK → seller_settlement.seller_settlement_id, ON DELETE CASCADE |
| 주문상품 ID * | order_product_id | UUID | ✓ | | |
| 라인 타입 * | line_type | VARCHAR(20) | ✓ | | SALE / REFUND (CHECK). SALE은 금액 ≥ 0, REFUND는 ≤ 0(애플리케이션 검증) |
| 라인 금액 * | line_amount | NUMERIC(12,2) | ✓ | | |
| 수수료율 * | fee_rate | NUMERIC(5,4) | ✓ | | |
| 수수료 금액 * | fee_amount | NUMERIC(12,2) | ✓ | | |
| 라인 정산 금액 * | line_settlement_amount | NUMERIC(12,2) | ✓ | | |
| 발생 일시 * | occurred_at | TIMESTAMP | ✓ | | |
| 생성 일시 * | created_at | TIMESTAMP | ✓ | | |

인덱스: `idx_seller_settlement_detail_parent_occurred` ON (seller_settlement_id, occurred_at) — V3에서 추가.
