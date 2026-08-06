package com.prompthub.ai.recommendation.infrastructure.client.product;

import com.prompthub.ai.recommendation.application.port.SimilarProductQuery;
import com.prompthub.product.grpc.GetSimilarProductsRequest;
import com.prompthub.product.grpc.GetSimilarProductsResponse;
import com.prompthub.product.grpc.ProductQueryServiceGrpc.ProductQueryServiceBlockingStub;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SimilarProductQueryClient implements SimilarProductQuery {

    /**
     * 이 호출의 지연 예산. 탐색 페이지가 뜨는 동안 곁들여 뜨는 섹션이라, 오래 붙들고 있느니
     * 추천을 포기하는 편이 낫다. 설정으로 빼지 않는다 — configs가 config server 이미지에
     * 구워져 yml을 고쳐도 재배포가 필요하므로 노브만 늘고 조정이 빨라지지 않는다.
     */
    private static final Duration DEADLINE = Duration.ofSeconds(2);

    private final ProductQueryServiceBlockingStub stub;

    public SimilarProductQueryClient(ProductQueryServiceBlockingStub stub) {
        this.stub = stub;
    }

    /**
     * 조회에 실패하면 <b>예외를 올리지 않고 빈 목록을 돌려준다.</b> 추천이 안 되는 것과 상품을
     * 못 사는 것은 다른 문제이고, 요구사항이 "추천이 중단돼도 구매는 정상"을 요구한다.
     * 빈 목록은 위에서 "추천할 게 없음"으로 흘러가 화면에서 섹션이 숨겨진다.
     *
     * <p>대신 warn으로 남긴다 — 조용히 사라지면 추천이 죽은 걸 아무도 모른다.
     */
    @Override
    public List<SeedRanking> findSimilarProducts(List<UUID> seedProductIds, int limitPerSeed) {
        try {
            GetSimilarProductsResponse response = stub
                    .withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                    .getSimilarProducts(GetSimilarProductsRequest.newBuilder()
                            .addAllSeedProductIds(seedProductIds.stream().map(UUID::toString).toList())
                            .setLimitPerSeed(limitPerSeed)
                            .build());

            return response.getRankingsList().stream()
                    .map(ranking -> new SeedRanking(
                            UUID.fromString(ranking.getSeedProductId()),
                            ranking.getProductsList().stream().map(this::toRecommendedProduct).toList()))
                    .toList();
        } catch (StatusRuntimeException e) {
            log.warn("유사 상품 조회에 실패해 추천을 건너뜁니다. seedCount={}", seedProductIds.size(), e);
            return List.of();
        }
    }

    /**
     * proto3 string은 null을 담지 못해 빈 문자열로 온다. 화면에서 "없음"으로 읽히도록 null로 되돌린다.
     *
     * <p>proto 메시지 타입을 정규화된 이름으로 쓴다 — 구현한 인터페이스의 중첩 record와 이름이
     * 같아, 이 클래스 안에서는 상속된 쪽이 import를 가린다.
     */
    private RecommendedProduct toRecommendedProduct(com.prompthub.product.grpc.RecommendedProduct product) {
        return new RecommendedProduct(
                UUID.fromString(product.getProductId()),
                product.getTitle(),
                emptyToNull(product.getProductType()),
                emptyToNull(product.getModel()),
                product.getAmount(),
                product.getRating(),
                product.getSalesCount(),
                product.getSellerId().isEmpty() ? null : UUID.fromString(product.getSellerId()),
                emptyToNull(product.getDescription()),
                emptyToNull(product.getThumbnailUrl()),
                product.getTagsList()
        );
    }

    private String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
