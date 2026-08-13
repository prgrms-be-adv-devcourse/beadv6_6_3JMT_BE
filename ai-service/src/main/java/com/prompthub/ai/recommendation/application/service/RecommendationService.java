package com.prompthub.ai.recommendation.application.service;

import com.prompthub.ai.recommendation.application.port.PersonalizedProductQuery;
import com.prompthub.ai.recommendation.application.port.PersonalizedProductQuery.RecommendedProduct;
import com.prompthub.ai.recommendation.application.usecase.RecommendationUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService implements RecommendationUseCase {

    private final PersonalizedProductQuery personalizedProductQuery;

    public RecommendationService(PersonalizedProductQuery personalizedProductQuery) {
        this.personalizedProductQuery = personalizedProductQuery;
    }

    @Override
    public List<RecommendedProduct> recommend(
            List<UUID> cartProductIds, List<UUID> purchasedProductIds, int limit) {
        List<UUID> cartIds = nullSafe(cartProductIds);
        List<UUID> purchaseIds = nullSafe(purchasedProductIds);
        if (cartIds.isEmpty() && purchaseIds.isEmpty()) {
            return List.of();
        }
        return personalizedProductQuery.findRecommendations(cartIds, purchaseIds, limit);
    }

    private List<UUID> nullSafe(List<UUID> productIds) {
        return productIds != null ? productIds : List.of();
    }
}
