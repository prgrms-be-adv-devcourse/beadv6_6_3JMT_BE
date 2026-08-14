package com.prompthub.ai.recommendation.infrastructure.client.product;

import com.prompthub.ai.recommendation.application.port.PersonalizedProductQuery;
import com.prompthub.product.grpc.GetSimilarProductsRequest;
import com.prompthub.product.grpc.GetSimilarProductsResponse;
import com.prompthub.product.grpc.ProductQueryServiceGrpc.ProductQueryServiceBlockingStub;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PersonalizedProductQueryClient implements PersonalizedProductQuery {

    private final ProductQueryServiceBlockingStub stub;
    private final Duration deadline;

    public PersonalizedProductQueryClient(
            ProductQueryServiceBlockingStub stub,
            @Value("${ai.recommendation.grpc-deadline:10s}") Duration deadline) {
        this.stub = stub;
        this.deadline = deadline;
    }

    @Override
    public List<RecommendedProduct> findRecommendations(
            List<UUID> cartProductIds, List<UUID> purchasedProductIds, int limit) {
        try {
            GetSimilarProductsResponse response = stub
                    .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .getSimilarProducts(GetSimilarProductsRequest.newBuilder()
                            .addAllCartProductIds(cartProductIds.stream().map(UUID::toString).toList())
                            .addAllPurchasedProductIds(purchasedProductIds.stream().map(UUID::toString).toList())
                            .setLimit(limit)
                            .build());
            return response.getProductsList().stream().map(this::toRecommendedProduct).toList();
        } catch (StatusRuntimeException exception) {
            log.warn("회원 추천 조회에 실패해 추천을 건너뜁니다. cartCount={}, purchaseCount={}",
                    cartProductIds.size(), purchasedProductIds.size(), exception);
            return List.of();
        }
    }

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
                product.getTagsList());
    }

    private String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
