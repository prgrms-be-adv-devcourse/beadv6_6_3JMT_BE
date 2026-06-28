package com.prompthub.order.infra.rest.client;

import java.util.List;
import java.util.UUID;

/**
 * product-service REST API 응답을 매핑하기 위한 DTO 클래스.
 * product-service 내부 패키지의 DTO를 직접 참조하지 않기 위해 order-service 내부에 정의합니다.
 */
public final class ProductFeignDto {

	private ProductFeignDto() {
	}

	public record ProductIdsRequest(List<UUID> productIds) {
	}

	public record OrderSnapshotResponse(
		UUID productId,
		UUID sellerId,
		String title,
		String productType,
		int amount,
		String model
	) {
	}

	public record CartSnapshotResponse(
		UUID productId,
		UUID sellerId,
		String productTitle,
		String productType,
		int productAmount,
		String thumbnailUrl,
		String sellerNickname,
		String productStatus
	) {
	}

	public record CartSnapshotsResponse(
		List<CartSnapshotResponse> products
	) {
	}

	public record ContentResponse(
		UUID productId,
		String content
	) {
	}
}
