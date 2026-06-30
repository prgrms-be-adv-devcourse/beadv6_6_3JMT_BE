package com.prompthub.order.infra.rest.client;

import com.prompthub.exception.BusinessException;
import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductCartSnapshot;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.global.exception.ErrorCode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile({"default", "local"})
@RequiredArgsConstructor
public class ProductRestClientAdapter implements ProductClient {

	private final ProductFeignClient productFeignClient;

	@Override
	public List<ProductOrderSnapshot> getOrderSnapshots(List<UUID> productIds) {
		try {
			return productFeignClient.getOrderSnapshots(new ProductFeignDto.ProductIdsRequest(productIds))
				.stream()
				.map(this::toOrderSnapshot)
				.toList();
		} catch (Exception exception) {
			log.error("상품 서비스 주문 스냅샷 REST 조회 실패: productIds={}", productIds, exception);
			throw new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
		}
	}

	@Override
	public ProductCartSnapshot getCartSnapshot(UUID productId) {
		try {
			return toCartSnapshot(productFeignClient.getCartSnapshot(productId));
		} catch (Exception exception) {
			log.error("상품 서비스 장바구니 스냅샷 REST 조회 실패: productId={}", productId, exception);
			throw new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
		}
	}

	@Override
	public List<ProductCartSnapshot> getCartSnapshots(List<UUID> productIds) {
		try {
			return productFeignClient.getCartSnapshots(new ProductFeignDto.ProductIdsRequest(productIds))
				.products()
				.stream()
				.map(this::toCartSnapshot)
				.toList();
		} catch (Exception exception) {
			log.error("상품 서비스 장바구니 스냅샷 목록 REST 조회 실패: productIds={}", productIds, exception);
			throw new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
		}
	}

	@Override
	public ProductContent getProductContent(UUID productId) {
		try {
			var response = productFeignClient.getProductContent(productId);
			return new ProductContent(response.productId(), response.content());
		} catch (Exception exception) {
			log.error("상품 서비스 콘텐츠 REST 조회 실패: productId={}", productId, exception);
			throw new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
		}
	}

	private ProductOrderSnapshot toOrderSnapshot(ProductFeignDto.OrderSnapshotResponse response) {
		return new ProductOrderSnapshot(
			response.productId(),
			response.sellerId(),
			response.title(),
			response.productType(),
			response.model(),
			response.amount()
		);
	}

	private ProductCartSnapshot toCartSnapshot(ProductFeignDto.CartSnapshotResponse response) {
		return new ProductCartSnapshot(
			response.productId(),
			response.productTitle(),
			response.productType(),
			response.productAmount(),
			response.thumbnailUrl(),
			response.sellerId(),
			response.sellerNickname(),
			response.productStatus()
		);
	}
}
