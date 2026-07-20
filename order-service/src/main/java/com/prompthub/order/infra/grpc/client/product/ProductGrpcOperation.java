package com.prompthub.order.infra.grpc.client.product;

enum ProductGrpcOperation {

	ORDER_SNAPSHOTS("productOrderGrpc", "GetOrderSnapshots"),
	CART_SNAPSHOTS("productQueryGrpc", "GetCartSnapshots"),
	PRODUCT_CONTENT("productQueryGrpc", "GetProductContent");

	private final String circuitBreakerName;
	private final String grpcMethod;

	ProductGrpcOperation(String circuitBreakerName, String grpcMethod) {
		this.circuitBreakerName = circuitBreakerName;
		this.grpcMethod = grpcMethod;
	}

	String circuitBreakerName() {
		return circuitBreakerName;
	}

	String grpcMethod() {
		return grpcMethod;
	}
}
