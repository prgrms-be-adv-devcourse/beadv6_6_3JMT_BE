package com.prompthub.user.wishlist.infrastructure.grpc;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import net.devh.boot.grpc.client.inject.GrpcClient;

import com.prompthub.user.grpc.product.GetProductsByIdsRequest;
import com.prompthub.user.grpc.product.GetProductsByIdsResponse;
import com.prompthub.user.grpc.product.Product;
import com.prompthub.user.grpc.product.ProductServiceGrpc;
import com.prompthub.user.wishlist.application.client.ProductClient;
import com.prompthub.user.wishlist.application.dto.ProductSummaryDto;

import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ProductGrpcClient implements ProductClient {

    @GrpcClient("product-service")
    private ProductServiceGrpc.ProductServiceBlockingStub productServiceStub;

    @Override
    public List<ProductSummaryDto> getProductsByIds(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        try {
            GetProductsByIdsRequest request = GetProductsByIdsRequest.newBuilder()
                    .addAllProductIds(productIds.stream().map(UUID::toString).toList())
                    .build();
            GetProductsByIdsResponse response = productServiceStub.getProductsByIds(request);
            return response.getProductsList().stream()
                    .map(this::toDto)
                    .toList();
        } catch (StatusRuntimeException e) {
            log.warn("product-service gRPC 호출 실패. productIds={}, status={}", productIds, e.getStatus(), e);
            return List.of();
        }
    }

    private ProductSummaryDto toDto(Product product) {
        String thumbnailUrl = product.getThumbnailUrl().isBlank() ? null : product.getThumbnailUrl();
        return new ProductSummaryDto(
                UUID.fromString(product.getProductId()),
                UUID.fromString(product.getSellerId()),
                product.getTitle(),
                product.getPrice(),
                thumbnailUrl,
                product.getCategory(),
                product.getModel(),
                product.getSalesCount(),
                product.getAverageRating(),
                product.getStatus()
        );
    }
}
