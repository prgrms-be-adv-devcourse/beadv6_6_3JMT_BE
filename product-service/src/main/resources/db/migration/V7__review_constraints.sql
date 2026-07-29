--
-- product-service V7
--
-- review 테이블에 설계 문서(docs/domain-glossary/product.md)에 명시된 제약 두 개를 추가한다.
-- 문서에는 "1상품 1리뷰 제약: UNIQUE(product_id, user_id), CHECK(rating BETWEEN 1 AND 5)"로
-- 적혀 있는데 V1 baseline DDL에는 둘 다 없었다. 문서가 앞서 있고 스키마가 못 따라간 경우다.
--

-- 1상품 1리뷰.
--
-- 지금은 ProductReviewService.upsertReview가 findByUserIdAndProductId로 조회한 뒤 있으면 수정,
-- 없으면 생성하는 애플리케이션 로직만으로 이걸 보장한다. 조회와 저장 사이에 같은 사용자의 다른
-- 요청이 끼면 양쪽 모두 "기존 리뷰 없음"으로 판단해 리뷰가 두 개 생길 수 있다.
--
-- 리뷰가 중복되면 평균 평점이 왜곡되고, 그 값이 상세 화면과 검색 색인(ratingAvg) 양쪽에 그대로
-- 반영된다. 애플리케이션 로직이 경합에 뚫려도 DB가 두 번째 저장을 막게 한다.
--
-- 리뷰는 항상 family 루트(coalesce(parent_id, id))에 붙으므로, 이 제약은 "한 사용자가 한 상품
-- 계열에 리뷰 하나"를 뜻한다. 상품을 수정해 새 버전이 생겨도 리뷰는 루트에 남아 평점이 이어진다.
ALTER TABLE review
    ADD CONSTRAINT uk_review_product_user UNIQUE (product_id, user_id);

-- 별점 범위.
--
-- ReviewUpsertRequest의 @Min(1) @Max(5)가 이미 막고 있어 실질 위험은 낮다. 다만 요청 DTO 검증은
-- REST 진입점에만 걸리는 방어이므로, 값 자체의 불변 조건은 테이블에도 둔다.
ALTER TABLE review
    ADD CONSTRAINT review_rating_check CHECK (rating BETWEEN 1 AND 5);
