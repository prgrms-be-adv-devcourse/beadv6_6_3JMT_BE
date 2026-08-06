package com.prompthub.order.infra.grpc.client.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductGrpcOperationTest {

	@Test
	void mapsOrderSnapshotOperationMetadata() {
		assertThat(ProductGrpcOperation.ORDER_SNAPSHOTS.circuitBreakerName()).isEqualTo("productOrderGrpc");
		assertThat(ProductGrpcOperation.ORDER_SNAPSHOTS.grpcMethod()).isEqualTo("GetOrderSnapshots");
	}

	@Test
	void mapsCartSnapshotOperationMetadata() {
		assertThat(ProductGrpcOperation.CART_SNAPSHOTS.circuitBreakerName()).isEqualTo("productQueryGrpc");
		assertThat(ProductGrpcOperation.CART_SNAPSHOTS.grpcMethod()).isEqualTo("GetCartSnapshots");
	}

	@Test
	void mapsProductContentOperationMetadata() {
		assertThat(ProductGrpcOperation.PRODUCT_CONTENT.circuitBreakerName()).isEqualTo("productQueryGrpc");
		assertThat(ProductGrpcOperation.PRODUCT_CONTENT.grpcMethod()).isEqualTo("GetProductContent");
	}
}
