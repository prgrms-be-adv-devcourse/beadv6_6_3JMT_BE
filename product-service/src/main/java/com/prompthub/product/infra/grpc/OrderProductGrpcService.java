package com.prompthub.product.infra.grpc;

import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.grpc.product.v1.GetCartSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetCartSnapshotsResponse;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsResponse;
import com.prompthub.grpc.product.v1.GetProductContentRequest;
import com.prompthub.grpc.product.v1.GetProductContentResponse;
import com.prompthub.grpc.product.v1.ProductCartSnapshotMessage;
import com.prompthub.grpc.product.v1.ProductInternalServiceGrpc;
import com.prompthub.grpc.product.v1.ProductOrderSnapshot;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderProductGrpcService extends ProductInternalServiceGrpc.ProductInternalServiceImplBase {

	private final ProductInternalUseCase productInternalUseCase;

	@Override
	public void getOrderSnapshots(GetOrderSnapshotsRequest request, StreamObserver<GetOrderSnapshotsResponse> responseObserver) {
		try {
			List<UUID> productIds = request.getProductIdsList().stream()
				.map(UUID::fromString)
				.toList();
			List<ProductOrderSnapshot> snapshots = productInternalUseCase.getOrderSnapshots(productIds).stream()
				.map(s -> ProductOrderSnapshot.newBuilder()
					.setProductId(s.productId().toString())
					.setSellerId(s.sellerId().toString())
					.setTitle(s.title())
					.setProductType(s.productType())
					.setAmount(s.amount())
					.setModel(s.model() != null ? s.model() : "")
					.build())
				.toList();
			responseObserver.onNext(GetOrderSnapshotsResponse.newBuilder()
				.addAllProducts(snapshots)
				.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.error("GetOrderSnapshots failed: productIds={}", request.getProductIdsList(), e);
			responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void getCartSnapshots(GetCartSnapshotsRequest request, StreamObserver<GetCartSnapshotsResponse> responseObserver) {
		try {
			List<UUID> productIds = request.getProductIdsList().stream()
				.map(UUID::fromString)
				.toList();
			var dtoList = productInternalUseCase.getCartSnapshots(productIds);
			List<ProductCartSnapshotMessage> snapshots = dtoList.stream()
				.map(s -> ProductCartSnapshotMessage.newBuilder()
					.setProductId(s.productId().toString())
					.setSellerId(s.sellerId().toString())
					.setSellerNickname(s.sellerNickname() != null ? s.sellerNickname() : "")
					.setTitle(s.productTitle())
					.setProductType(s.productType() != null ? s.productType() : "")
					.setAmount(s.productAmount())
					.setThumbnailUrl(s.thumbnailUrl() != null ? s.thumbnailUrl() : "")
					.setProductStatus(s.productStatus() != null ? s.productStatus() : "")
					.build())
				.toList();
			responseObserver.onNext(GetCartSnapshotsResponse.newBuilder()
				.addAllProducts(snapshots)
				.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.error("GetCartSnapshots failed: productIds={}", request.getProductIdsList(), e);
			responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void getProductContent(GetProductContentRequest request, StreamObserver<GetProductContentResponse> responseObserver) {
		try {
			UUID productId = UUID.fromString(request.getProductId());
			var result = productInternalUseCase.getProductContent(productId);
			responseObserver.onNext(GetProductContentResponse.newBuilder()
				.setProductId(result.productId().toString())
				.setContent(result.content() != null ? result.content() : "")
				.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.error("GetProductContent failed: productId={}", request.getProductId(), e);
			responseObserver.onError(Status.NOT_FOUND.withDescription("Product not found: " + request.getProductId()).asRuntimeException());
		}
	}
}
