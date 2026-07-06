package com.prompthub.order.infra.grpc.client.product;

import com.prompthub.exception.BusinessException;
import com.prompthub.grpc.product.v1.GetCartSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetProductContentRequest;
import com.prompthub.grpc.product.v1.ProductCartSnapshotMessage;
import com.prompthub.grpc.product.v1.ProductInternalServiceGrpc;
import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductCartSnapshot;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.global.exception.ErrorCode;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"dev", "prod"})
public class ProductGrpcClientAdapter implements ProductClient {

	private final ProductInternalServiceGrpc.ProductInternalServiceBlockingStub stub;
	private final int deadlineMs;

	public ProductGrpcClientAdapter(
		ProductInternalServiceGrpc.ProductInternalServiceBlockingStub stub,
		@Value("${prompthub.grpc.product.deadline-ms:2000}") int deadlineMs
	) {
		this.stub = stub;
		this.deadlineMs = deadlineMs;
	}

	@Override
	public List<ProductOrderSnapshot> getOrderSnapshots(List<UUID> productIds) {
		try {
			return withDeadline().getOrderSnapshots(GetOrderSnapshotsRequest.newBuilder()
					.addAllProductIds(toStrings(productIds))
					.build())
				.getProductsList()
				.stream()
				.map(this::toOrderSnapshot)
				.toList();
		} catch (StatusRuntimeException exception) {
			throw productServiceUnavailable();
		}
	}

	@Override
	public ProductCartSnapshot getCartSnapshot(UUID productId) {
		try {
			var response = withDeadline().getCartSnapshots(GetCartSnapshotsRequest.newBuilder()
					.addProductIds(productId.toString())
					.build())
				.getProductsList();
			if (response.isEmpty()) {
				throw productServiceUnavailable();
			}
			return toCartSnapshot(response.getFirst());
		} catch (StatusRuntimeException exception) {
			throw productServiceUnavailable();
		}
	}

	@Override
	public List<ProductCartSnapshot> getCartSnapshots(List<UUID> productIds) {
		try {
			return withDeadline().getCartSnapshots(GetCartSnapshotsRequest.newBuilder()
					.addAllProductIds(toStrings(productIds))
					.build())
				.getProductsList()
				.stream()
				.map(this::toCartSnapshot)
				.toList();
		} catch (StatusRuntimeException exception) {
			throw productServiceUnavailable();
		}
	}

	@Override
	public ProductContent getProductContent(UUID productId) {
		try {
			var response = withDeadline().getProductContent(GetProductContentRequest.newBuilder()
				.setProductId(productId.toString())
				.build());
			return new ProductContent(UUID.fromString(response.getProductId()), response.getContent());
		} catch (StatusRuntimeException exception) {
			throw productServiceUnavailable();
		}
	}



	private ProductInternalServiceGrpc.ProductInternalServiceBlockingStub withDeadline() {
		return stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
	}

	private List<String> toStrings(List<UUID> values) {
		return values.stream()
			.map(UUID::toString)
			.toList();
	}

	private ProductOrderSnapshot toOrderSnapshot(com.prompthub.grpc.product.v1.ProductOrderSnapshot response) {
		return new ProductOrderSnapshot(
			UUID.fromString(response.getProductId()),
			UUID.fromString(response.getSellerId()),
			response.getTitle(),
			response.getProductType(),
			response.getModel(),
			response.getAmount()
		);
	}

	private ProductCartSnapshot toCartSnapshot(ProductCartSnapshotMessage response) {
		return new ProductCartSnapshot(
			UUID.fromString(response.getProductId()),
			response.getTitle(),
			response.getProductType(),
			response.getAmount(),
			response.getThumbnailUrl(),
			UUID.fromString(response.getSellerId()),
			response.getSellerNickname(),
			response.getProductStatus()
		);
	}

	private BusinessException productServiceUnavailable() {
		return new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
	}
}
