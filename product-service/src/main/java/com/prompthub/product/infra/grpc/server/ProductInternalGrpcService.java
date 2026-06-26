package com.prompthub.product.infra.grpc.server;

import com.prompthub.grpc.product.v1.GetCartSnapshotRequest;
import com.prompthub.grpc.product.v1.GetCartSnapshotResponse;
import com.prompthub.grpc.product.v1.GetCartSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetCartSnapshotsResponse;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsResponse;
import com.prompthub.grpc.product.v1.GetProductContentRequest;
import com.prompthub.grpc.product.v1.GetProductContentResponse;
import com.prompthub.grpc.product.v1.ProductInternalServiceGrpc;
import com.prompthub.grpc.product.v1.ProductOrderSnapshotResponse;
import com.prompthub.grpc.product.v1.UpsertReviewRequest;
import com.prompthub.grpc.product.v1.UpsertReviewResponse;
import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductInternalGrpcService extends ProductInternalServiceGrpc.ProductInternalServiceImplBase {

	private final ProductInternalUseCase productInternalUseCase;

	@Override
	public void getOrderSnapshots(
		GetOrderSnapshotsRequest request,
		StreamObserver<GetOrderSnapshotsResponse> responseObserver
	) {
		try {
			List<UUID> productIds = request.getProductIdsList().stream()
				.map(UUID::fromString)
				.toList();
			GetOrderSnapshotsResponse response = GetOrderSnapshotsResponse.newBuilder()
				.addAllProducts(productInternalUseCase.getOrderSnapshots(productIds).stream()
					.map(this::toOrderSnapshotResponse)
					.toList())
				.build();
			complete(responseObserver, response);
		} catch (RuntimeException exception) {
			responseObserver.onError(toStatus(exception).asRuntimeException());
		}
	}

	@Override
	public void getCartSnapshot(
		GetCartSnapshotRequest request,
		StreamObserver<GetCartSnapshotResponse> responseObserver
	) {
		try {
			ProductCartSnapshotResponse snapshot =
				productInternalUseCase.getCartSnapshot(UUID.fromString(request.getProductId()));
			complete(responseObserver, toCartSnapshotResponse(snapshot));
		} catch (RuntimeException exception) {
			responseObserver.onError(toStatus(exception).asRuntimeException());
		}
	}

	@Override
	public void getCartSnapshots(
		GetCartSnapshotsRequest request,
		StreamObserver<GetCartSnapshotsResponse> responseObserver
	) {
		try {
			List<UUID> productIds = request.getProductIdsList().stream()
				.map(UUID::fromString)
				.toList();
			GetCartSnapshotsResponse response = GetCartSnapshotsResponse.newBuilder()
				.addAllProducts(productInternalUseCase.getCartSnapshots(productIds).stream()
					.map(this::toCartSnapshotResponse)
					.toList())
				.build();
			complete(responseObserver, response);
		} catch (RuntimeException exception) {
			responseObserver.onError(toStatus(exception).asRuntimeException());
		}
	}

	@Override
	public void getProductContent(
		GetProductContentRequest request,
		StreamObserver<GetProductContentResponse> responseObserver
	) {
		try {
			ProductContentResponse content =
				productInternalUseCase.getProductContent(UUID.fromString(request.getProductId()));
			GetProductContentResponse response = GetProductContentResponse.newBuilder()
				.setProductId(content.productId().toString())
				.setContent(valueOrEmpty(content.content()))
				.build();
			complete(responseObserver, response);
		} catch (RuntimeException exception) {
			responseObserver.onError(toStatus(exception).asRuntimeException());
		}
	}

	@Override
	public void upsertReview(
		UpsertReviewRequest request,
		StreamObserver<UpsertReviewResponse> responseObserver
	) {
		try {
			productInternalUseCase.upsertReview(
				UUID.fromString(request.getBuyerId()),
				UUID.fromString(request.getProductId()),
				request.getRating()
			);
			complete(responseObserver, UpsertReviewResponse.getDefaultInstance());
		} catch (RuntimeException exception) {
			responseObserver.onError(toStatus(exception).asRuntimeException());
		}
	}

	private ProductOrderSnapshotResponse toOrderSnapshotResponse(
		com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse snapshot
	) {
		return ProductOrderSnapshotResponse.newBuilder()
			.setProductId(snapshot.productId().toString())
			.setSellerId(snapshot.sellerId().toString())
			.setTitle(valueOrEmpty(snapshot.title()))
			.setProductType(valueOrEmpty(snapshot.productType()))
			.setAmount(snapshot.amount())
			.build();
	}

	private GetCartSnapshotResponse toCartSnapshotResponse(ProductCartSnapshotResponse snapshot) {
		return GetCartSnapshotResponse.newBuilder()
			.setProductId(snapshot.productId().toString())
			.setTitle(valueOrEmpty(snapshot.productTitle()))
			.setProductType(valueOrEmpty(snapshot.productType()))
			.setAmount(snapshot.productAmount())
			.setThumbnailUrl(valueOrEmpty(snapshot.thumbnailUrl()))
			.setSellerId(snapshot.sellerId().toString())
			.setSellerNickname(valueOrEmpty(snapshot.sellerNickname()))
			.setStatus(valueOrEmpty(snapshot.productStatus()))
			.build();
	}

	private <T> void complete(StreamObserver<T> responseObserver, T response) {
		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}

	private Status toStatus(RuntimeException exception) {
		if (exception instanceof IllegalArgumentException) {
			return Status.INVALID_ARGUMENT.withDescription(exception.getMessage());
		}
		if (exception instanceof ProductException) {
			return Status.NOT_FOUND.withDescription(exception.getMessage());
		}
		return Status.INTERNAL.withDescription(exception.getMessage());
	}

	private String valueOrEmpty(String value) {
		return value == null ? "" : value;
	}
}
