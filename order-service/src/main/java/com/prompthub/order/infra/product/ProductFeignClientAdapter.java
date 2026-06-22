package com.prompthub.order.infra.product;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductCartSnapshot;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.exception.BusinessException;
import com.prompthub.order.global.exception.ErrorCode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@Profile({"dev", "prod"})
@RequiredArgsConstructor
public class ProductFeignClientAdapter implements ProductClient {

    private final ProductFeignClient productFeignClient;

    @Override
    public List<ProductOrderSnapshot> getOrderSnapshots(List<UUID> productIds) {
        try {
            return productFeignClient.getOrderSnapshots(new ProductOrderSnapshotRequest(productIds));
        } catch (FeignException exception) {
            throw new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public ProductCartSnapshot getCartSnapshot(UUID productId) {
        try {
            return productFeignClient.getCartSnapshot(productId);
        } catch (FeignException exception) {
            throw new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public List<ProductCartSnapshot> getCartSnapshots(List<UUID> productIds) {
        try {
            return productFeignClient.getCartSnapshots(new ProductCartSnapshotRequest(productIds));
        } catch (FeignException exception) {
            throw new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public ProductContent getProductContent(UUID productId) {
        try {
            return productFeignClient.getProductContent(productId);
        } catch (FeignException exception) {
            throw new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void upsertReview(UUID buyerId, UUID productId, int rating) {
        try {
            productFeignClient.upsertReview(new ProductReviewUpsertRequest(buyerId, productId, rating));
        } catch (FeignException exception) {
            throw new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
    }
}
