package com.prompthub.ai.recommendation.application.port;

import java.util.List;
import java.util.UUID;

/**
 * 기준 상품마다 비슷한 상품 순위를 가져오는 아웃바운드 포트.
 *
 * <p>벡터 유사도 계산과 상품 데이터는 product-service가 소유한다. 이 포트는 "이 상품과 가까운
 * 것"만 물어보고, <b>여러 기준의 순위를 어떻게 합칠지는 묻지 않는다</b> — 그 판단은 이 서비스가
 * 한다. 합쳐진 결과를 받아오면 추천을 독립 서비스로 둔 의미가 없어진다.
 */
public interface SimilarProductQuery {

    /**
     * @param seedProductIds 사용자 활동에서 추린 기준 상품들
     * @param limitPerSeed   기준 하나당 받아올 후보 수. 합산 후 잘라내고 이미 담은·산 상품도
     *                       빼야 하므로 최종 노출 수보다 넉넉해야 한다
     * @return 요청한 기준 순서대로의 순위 목록. 기준 상품이 판매 중이 아니거나 임베딩이 아직
     *         없으면 그 기준의 {@code products}만 비어 돌아온다
     */
    List<SeedRanking> findSimilarProducts(List<UUID> seedProductIds, int limitPerSeed);

    /** 기준 상품 하나에 대한 순위. {@code products}는 가까운 순으로 정렬돼 있다. */
    record SeedRanking(UUID seedProductId, List<RecommendedProduct> products) {
    }

    /** 추천 카드가 그리는 값. 상세 화면용 필드(본문·파일 URL)는 담지 않는다. */
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
