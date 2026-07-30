package com.prompthub.ai.recommendation.application.service;

import com.prompthub.ai.recommendation.application.port.SimilarProductQuery;
import com.prompthub.ai.recommendation.application.port.SimilarProductQuery.RecommendedProduct;
import com.prompthub.ai.recommendation.application.port.SimilarProductQuery.SeedRanking;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationServiceTest {

    private static final UUID CART_A = UUID.fromString("0a000000-0000-0000-0000-000000000001");
    private static final UUID CART_B = UUID.fromString("0a000000-0000-0000-0000-000000000002");
    private static final UUID BOUGHT = UUID.fromString("0b000000-0000-0000-0000-000000000001");

    private static final UUID REC_1 = UUID.fromString("0c000000-0000-0000-0000-000000000001");
    private static final UUID REC_2 = UUID.fromString("0c000000-0000-0000-0000-000000000002");
    private static final UUID REC_3 = UUID.fromString("0c000000-0000-0000-0000-000000000003");

    private final List<List<UUID>> requestedSeeds = new ArrayList<>();

    @Test
    @DisplayName("활동 내역이 없으면 빈 목록이고, 상품 서비스를 호출하지도 않는다")
    void noActivityReturnsEmptyWithoutCallingProductService() {
        AtomicInteger calls = new AtomicInteger();
        RecommendationService service = new RecommendationService((seeds, limitPerSeed) -> {
            calls.incrementAndGet();
            return List.of();
        });

        assertThat(service.recommend(List.of(), List.of(), 4)).isEmpty();
        assertThat(service.recommend(null, null, 4)).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("이미 담았거나 구매한 상품은 추천에서 빠진다")
    void excludesProductsUserAlreadyHas() {
        RecommendationService service = new RecommendationService(
                fixedRankings(Map.of(CART_A, List.of(CART_B, BOUGHT, REC_1))));

        List<RecommendedProduct> recommended = service.recommend(List.of(CART_A, CART_B), List.of(BOUGHT), 4);

        assertThat(recommended).extracting(RecommendedProduct::id).containsExactly(REC_1);
    }

    @Test
    @DisplayName("장바구니 기준이 구매 기준보다 앞선다")
    void cartOutweighsPurchase() {
        RecommendationService service = new RecommendationService(fixedRankings(Map.of(
                CART_A, List.of(REC_1),
                BOUGHT, List.of(REC_2))));

        List<RecommendedProduct> recommended = service.recommend(List.of(CART_A), List.of(BOUGHT), 4);

        assertThat(recommended).extracting(RecommendedProduct::id).containsExactly(REC_1, REC_2);
    }

    @Test
    @DisplayName("요청한 개수만큼만 돌려준다")
    void trimsToRequestedLimit() {
        RecommendationService service = new RecommendationService(
                fixedRankings(Map.of(CART_A, List.of(REC_1, REC_2, REC_3))));

        assertThat(service.recommend(List.of(CART_A), List.of(), 2))
                .extracting(RecommendedProduct::id)
                .containsExactly(REC_1, REC_2);
    }

    @Test
    @DisplayName("활동이 많아도 기준은 신호당 3개까지만 조회한다")
    void capsSeedsPerSignal() {
        List<UUID> manyCartItems = List.of(
                CART_A, CART_B,
                UUID.fromString("0a000000-0000-0000-0000-000000000003"),
                UUID.fromString("0a000000-0000-0000-0000-000000000004"),
                UUID.fromString("0a000000-0000-0000-0000-000000000005"));
        RecommendationService service = new RecommendationService(recordingEmptyQuery());

        service.recommend(manyCartItems, List.of(), 4);

        assertThat(requestedSeeds).hasSize(1);
        assertThat(requestedSeeds.get(0)).hasSize(3);
    }

    @Test
    @DisplayName("장바구니에도 있고 구매도 한 상품은 기준으로 한 번만 조회한다")
    void doesNotQuerySameSeedTwice() {
        RecommendationService service = new RecommendationService(recordingEmptyQuery());

        service.recommend(List.of(CART_A), List.of(CART_A), 4);

        assertThat(requestedSeeds.get(0)).containsExactly(CART_A);
    }

    @Test
    @DisplayName("상품 서비스가 빈 결과를 주면 추천도 비어 있다 — 예외로 번지지 않는다")
    void emptyRankingsProduceEmptyRecommendation() {
        RecommendationService service = new RecommendationService((seeds, limitPerSeed) -> List.of());

        assertThat(service.recommend(List.of(CART_A), List.of(BOUGHT), 4)).isEmpty();
    }

    private SimilarProductQuery recordingEmptyQuery() {
        return (seeds, limitPerSeed) -> {
            requestedSeeds.add(seeds);
            return List.of();
        };
    }

    /** 기준별로 정해진 추천 목록을 돌려주는 가짜 포트. 요청한 기준 순서를 그대로 유지한다. */
    private SimilarProductQuery fixedRankings(Map<UUID, List<UUID>> rankingsBySeed) {
        return (seeds, limitPerSeed) -> seeds.stream()
                .map(seed -> new SeedRanking(
                        seed,
                        rankingsBySeed.getOrDefault(seed, List.of()).stream()
                                .map(RecommendationServiceTest::product)
                                .toList()))
                .toList();
    }

    private static RecommendedProduct product(UUID id) {
        return new RecommendedProduct(
                id, "상품 " + id, "PROMPT", "GPT-5", 10000, 4.5, 3, null, "설명", null, List.of());
    }
}
