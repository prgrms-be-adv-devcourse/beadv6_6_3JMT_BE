package com.prompthub.ai.recommendation.application.usecase;

import com.prompthub.ai.recommendation.application.port.PersonalizedProductQuery.RecommendedProduct;
import java.util.List;
import java.util.UUID;

public interface RecommendationUseCase {

    /**
     * 사용자 활동을 기준으로 맞춤 추천을 고른다.
     *
     * <p>활동 내역은 order-service가 소유하므로 호출자가 실어 보낸다. 이 서비스는 저장하지 않고
     * 받은 것만으로 판단한다.
     *
     * @param cartProductIds      장바구니에 담은 상품. 지금 사려는 것이라 더 무겁게 본다
     * @param purchasedProductIds 구매한 상품. 확정된 취향이지만 과거다
     * @param limit               최종 노출 개수
     * @return 추천 순서대로의 상품 목록. 활동이 없으면 빈 목록 — 화면에서 섹션을 숨기면 된다
     */
    List<RecommendedProduct> recommend(List<UUID> cartProductIds, List<UUID> purchasedProductIds, int limit);
}
