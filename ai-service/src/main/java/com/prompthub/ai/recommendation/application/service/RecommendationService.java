package com.prompthub.ai.recommendation.application.service;

import com.prompthub.ai.recommendation.application.port.SimilarProductQuery;
import com.prompthub.ai.recommendation.application.port.SimilarProductQuery.RecommendedProduct;
import com.prompthub.ai.recommendation.application.port.SimilarProductQuery.SeedRanking;
import com.prompthub.ai.recommendation.application.usecase.RecommendationUseCase;
import com.prompthub.ai.recommendation.domain.WeightedRankFusion;
import com.prompthub.ai.recommendation.domain.WeightedRankFusion.WeightedRanking;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 사용자 활동에서 기준 상품을 고르고, 기준별 순위를 가중치를 실어 합친다.
 *
 * <p>추천의 <b>판단</b>이 여기 있다 — 어떤 활동에 얼마나 무게를 둘지, 무엇을 빼고 몇 개를
 * 보여줄지. product-service는 "이 상품과 가까운 것"만 답하고 누구에게 무엇을 추천할지 모른다.
 */
@Service
public class RecommendationService implements RecommendationUseCase {

    /**
     * 장바구니가 구매보다 무겁다. 장바구니는 <b>지금</b> 사려는 것이고 구매는 이미 끝난 과거다.
     * 지금 관심사에 가까운 쪽을 앞세우는 편이 화면에서 덜 뜬금없다.
     */
    private static final double CART_WEIGHT = 1.0;
    private static final double PURCHASE_WEIGHT = 0.7;

    /**
     * 활동 하나당 기준으로 삼을 최대 개수. 장바구니를 20개 담은 사용자에게 gRPC 왕복이 20배가
     * 되지 않게 막는다. 최근 것이 앞에 온다는 전제로 앞에서 자른다.
     */
    private static final int MAX_SEEDS_PER_SIGNAL = 3;

    /**
     * 기준 하나당 받아올 후보 수 = 최종 노출 수 × 이 값. 합산으로 순서가 바뀌고 이미 담은·산
     * 상품이 빠지므로 노출 수만큼만 받으면 자리가 빈다.
     */
    private static final int CANDIDATE_MULTIPLIER = 4;

    private final SimilarProductQuery similarProductQuery;

    public RecommendationService(SimilarProductQuery similarProductQuery) {
        this.similarProductQuery = similarProductQuery;
    }

    @Override
    public List<RecommendedProduct> recommend(
            List<UUID> cartProductIds, List<UUID> purchasedProductIds, int limit) {
        List<UUID> cartSeeds = seedsOf(cartProductIds);
        List<UUID> purchaseSeeds = seedsOf(purchasedProductIds);

        // 같은 상품이 장바구니에도 있고 구매도 했으면 기준으로는 한 번만 조회한다.
        // 가중치는 아래에서 기준 id로 찾아 붙이므로 양쪽 몫(1.0 + 0.7)이 그대로 반영된다.
        List<UUID> allSeeds = new ArrayList<>(cartSeeds);
        allSeeds.addAll(purchaseSeeds);
        allSeeds = allSeeds.stream().distinct().toList();
        if (allSeeds.isEmpty()) {
            // 활동이 없으면 추천할 근거가 없다. 인기순 같은 폴백을 만들지 않는다 —
            // 개인화가 아닌 것을 개인화 자리에 채우면 사용자를 속이는 셈이다.
            return List.of();
        }

        List<SeedRanking> rankings =
                similarProductQuery.findSimilarProducts(allSeeds, limit * CANDIDATE_MULTIPLIER);

        Map<UUID, RecommendedProduct> byId = new LinkedHashMap<>();
        Map<UUID, List<UUID>> rankedIdsBySeed = new LinkedHashMap<>();
        for (SeedRanking ranking : rankings) {
            List<UUID> rankedIds = new ArrayList<>();
            for (RecommendedProduct product : ranking.products()) {
                byId.putIfAbsent(product.id(), product);
                rankedIds.add(product.id());
            }
            rankedIdsBySeed.put(ranking.seedProductId(), rankedIds);
        }

        List<WeightedRanking> weighted = new ArrayList<>();
        addWeighted(weighted, cartSeeds, rankedIdsBySeed, CART_WEIGHT);
        addWeighted(weighted, purchaseSeeds, rankedIdsBySeed, PURCHASE_WEIGHT);

        Set<UUID> alreadyHave = new HashSet<>(nullSafe(cartProductIds));
        alreadyHave.addAll(nullSafe(purchasedProductIds));

        return WeightedRankFusion.fuse(weighted).stream()
                .filter(id -> !alreadyHave.contains(id))
                .map(byId::get)
                .limit(limit)
                .toList();
    }

    private void addWeighted(
            List<WeightedRanking> target,
            List<UUID> seeds,
            Map<UUID, List<UUID>> rankedIdsBySeed,
            double weight) {
        for (UUID seed : seeds) {
            List<UUID> ranked = rankedIdsBySeed.getOrDefault(seed, List.of());
            if (!ranked.isEmpty()) {
                target.add(new WeightedRanking(weight, ranked));
            }
        }
    }

    /** 같은 상품이 장바구니와 구매에 함께 있으면 기준이 두 번 잡히므로 앞에서 한 번만 센다. */
    private List<UUID> seedsOf(List<UUID> productIds) {
        return nullSafe(productIds).stream()
                .distinct()
                .limit(MAX_SEEDS_PER_SIGNAL)
                .toList();
    }

    private List<UUID> nullSafe(List<UUID> productIds) {
        return productIds != null ? productIds : List.of();
    }
}
