package com.prompthub.product.domain.model.projection;

import java.util.UUID;

/**
 * 임베딩이 가까운 상품 후보. 재정렬에 필요한 것만 담는다.
 *
 * <p>제목·가격·평점 같은 표시용 필드는 담지 않는다. 담으려면 kNN 쿼리에 평점 집계와 family
 * 판매수 합산을 다시 써야 하는데, 그러면 목록 조회 JPQL과 같은 로직이 두 군데가 된다.
 * 후보를 고른 뒤 기존 상품 조회로 채운다.
 *
 * @param distance 코사인 거리. 작을수록 비슷하다
 */
public record SimilarProductProjection(UUID id, String productType, double distance) {
}
