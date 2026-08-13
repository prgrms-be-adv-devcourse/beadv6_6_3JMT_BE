package com.prompthub.ai.recommendation.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.ai.recommendation.application.port.PersonalizedProductQuery.RecommendedProduct;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecommendationServiceTest {

    private static final UUID CART = UUID.fromString("0a000000-0000-0000-0000-000000000001");
    private static final UUID PURCHASED = UUID.fromString("0b000000-0000-0000-0000-000000000001");
    private static final UUID RECOMMENDED = UUID.fromString("0c000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("활동 내역이 없으면 상품 서비스를 호출하지 않고 빈 목록을 준다")
    void noActivityReturnsEmptyWithoutQuery() {
        AtomicInteger calls = new AtomicInteger();
        RecommendationService service = new RecommendationService((cart, purchased, limit) -> {
            calls.incrementAndGet();
            return List.of();
        });

        assertThat(service.recommend(null, List.of(), 4)).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("장바구니와 구매 활동을 한 번에 넘겨 검증된 추천 순서를 그대로 반환한다")
    void returnsValidatedRecommendationsFromProductService() {
        RecommendedProduct product = product(RECOMMENDED);
        RecommendationService service = new RecommendationService((cart, purchased, limit) -> {
            assertThat(cart).containsExactly(CART);
            assertThat(purchased).containsExactly(PURCHASED);
            assertThat(limit).isEqualTo(4);
            return List.of(product);
        });

        assertThat(service.recommend(List.of(CART), List.of(PURCHASED), 4)).containsExactly(product);
    }

    private static RecommendedProduct product(UUID id) {
        return new RecommendedProduct(
                id, "상품", "PROMPT", "GPT-5", 10000, 4.5, 3, null, "설명", null, List.of());
    }
}
