package com.prompthub.ai.recommendation.application.port;

import java.util.List;
import java.util.UUID;

/** 사용자 활동을 기준으로 관련성 검증까지 끝난 상품을 product-service에 요청한다. */
public interface PersonalizedProductQuery {

    List<RecommendedProduct> findRecommendations(
            List<UUID> cartProductIds, List<UUID> purchasedProductIds, int limit);

    record RecommendedProduct(
            UUID id,
            String title,
            String productType,
            String model,
            int amount,
            double rating,
            int salesCount,
            UUID sellerId,
            String desc,
            String thumbnailUrl,
            List<String> tags
    ) {
    }
}
