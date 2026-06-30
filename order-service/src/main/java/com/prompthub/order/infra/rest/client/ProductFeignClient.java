package com.prompthub.order.infra.rest.client;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "product-service", url = "${prompthub.clients.product-service.url:}", path = "/internal/products")
public interface ProductFeignClient {

	@PostMapping("/order-snapshots")
	List<ProductFeignDto.OrderSnapshotResponse> getOrderSnapshots(
		@RequestBody ProductFeignDto.ProductIdsRequest request
	);

	@GetMapping("/{productId}/cart-snapshot")
	ProductFeignDto.CartSnapshotResponse getCartSnapshot(
		@PathVariable("productId") UUID productId
	);

	@PostMapping("/cart-snapshots")
	ProductFeignDto.CartSnapshotsResponse getCartSnapshots(
		@RequestBody ProductFeignDto.ProductIdsRequest request
	);

	@GetMapping("/{productId}/content")
	ProductFeignDto.ContentResponse getProductContent(
		@PathVariable("productId") UUID productId
	);
}
