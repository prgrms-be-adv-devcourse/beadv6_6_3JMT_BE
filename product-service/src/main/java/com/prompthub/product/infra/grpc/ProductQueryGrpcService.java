package com.prompthub.product.infra.grpc;

import com.prompthub.product.application.usecase.ProductGrpcUseCase;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.grpc.GetCartSnapshotsRequest;
import com.prompthub.product.grpc.GetCartSnapshotsResponse;
import com.prompthub.product.grpc.GetOrderSnapshotsRequest;
import com.prompthub.product.grpc.GetOrderSnapshotsResponse;
import com.prompthub.product.grpc.GetProductContentRequest;
import com.prompthub.product.grpc.GetProductContentResponse;
import com.prompthub.product.grpc.ProductCartSnapshotMessage;
import com.prompthub.product.grpc.ProductContentResult;
import com.prompthub.product.grpc.ProductOrderSnapshot;
import com.prompthub.product.grpc.ProductQueryServiceGrpc;
import com.prompthub.product.grpc.PurchasedProductContent;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * product-service가 서버로서 제공하는 gRPC 계약(루트 grpc/product/product_query.proto)의 단일 구현.
 * order(스냅샷/콘텐츠) 호출을 서빙한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductQueryGrpcService extends ProductQueryServiceGrpc.ProductQueryServiceImplBase {

	private final ProductGrpcUseCase productGrpcUseCase;

	@Override
	public void getOrderSnapshots(GetOrderSnapshotsRequest request, StreamObserver<GetOrderSnapshotsResponse> responseObserver) {
		try {
			List<UUID> productIds = request.getProductIdsList().stream()
				.map(UUID::fromString)
				.toList();
			List<ProductOrderSnapshot> snapshots = productGrpcUseCase.getOrderSnapshots(productIds).stream()
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
			var dtoList = productGrpcUseCase.getCartSnapshots(productIds);
			List<ProductCartSnapshotMessage> snapshots = dtoList.stream()
				.map(s -> ProductCartSnapshotMessage.newBuilder()
					.setProductId(s.productId().toString())
					.setSellerId(s.sellerId().toString())
					.setSellerNickname(s.sellerNickname() != null ? s.sellerNickname() : "")
					.setTitle(s.productTitle())
					.setProductType(s.productType() != null ? s.productType() : "")
					.setAmount(s.productAmount())
					.setThumbnailUrl(s.thumbnailUrl() != null ? s.thumbnailUrl() : "")
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
			GetProductContentResponse response = switch (request.getPurpose()) {
				case ORDER_SNAPSHOT -> orderSnapshotResponse(request);
				case CART_SNAPSHOT -> cartSnapshotResponse(request);
				case PURCHASED_CONTENT -> purchasedContentResponse(requireSingleProductId(request));
				default -> legacyContentResponse(requireSingleProductId(request)); // UNSPECIFIED, UNRECOGNIZED
			};
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		} catch (IllegalArgumentException e) {
			log.warn("GetProductContent invalid request: purpose={}, productId={}, productIds={}",
				request.getPurpose(), request.getProductId(), request.getProductIdsList(), e);
			responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (ProductException e) {
			log.warn("GetProductContent not found: productId={}", request.getProductId(), e);
			responseObserver.onError(Status.NOT_FOUND
				.withDescription("Product not found: " + request.getProductId()).asRuntimeException());
		} catch (Exception e) {
			log.error("GetProductContent failed: purpose={}, productId={}, productIds={}",
				request.getPurpose(), request.getProductId(), request.getProductIdsList(), e);
			responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	private GetProductContentResponse orderSnapshotResponse(GetProductContentRequest request) {
		List<UUID> productIds = requireBatchProductIds(request);
		List<ProductContentResult> results = productGrpcUseCase.getOrderSnapshots(productIds).stream()
			.map(this::toOrderSnapshotResult)
			.toList();
		return GetProductContentResponse.newBuilder().addAllResults(results).build();
	}

	private GetProductContentResponse cartSnapshotResponse(GetProductContentRequest request) {
		List<UUID> productIds = requireBatchProductIds(request);
		List<ProductContentResult> results = productGrpcUseCase.getCartSnapshots(productIds).stream()
			.map(this::toCartSnapshotResult)
			.toList();
		return GetProductContentResponse.newBuilder().addAllResults(results).build();
	}

	private GetProductContentResponse purchasedContentResponse(UUID productId) {
		ProductContentResponse result = productGrpcUseCase.getProductContent(productId);
		return GetProductContentResponse.newBuilder()
			.addResults(ProductContentResult.newBuilder().setPurchasedContent(toPurchasedContent(result)).build())
			.build();
	}

	private GetProductContentResponse legacyContentResponse(UUID productId) {
		ProductContentResponse result = productGrpcUseCase.getProductContent(productId);
		String content = result.content() != null ? result.content() : "";
		return GetProductContentResponse.newBuilder()
			.setProductId(result.productId().toString())
			.setContent(content)
			.addResults(ProductContentResult.newBuilder().setPurchasedContent(toPurchasedContent(result)).build())
			.build();
	}

	private ProductContentResult toOrderSnapshotResult(ProductOrderSnapshotResponse s) {
		return ProductContentResult.newBuilder().setOrderSnapshot(ProductOrderSnapshot.newBuilder()
			.setProductId(s.productId().toString())
			.setSellerId(s.sellerId().toString())
			.setTitle(s.title())
			.setProductType(s.productType())
			.setAmount(s.amount())
			.setModel(s.model() != null ? s.model() : "")
			.build()).build();
	}

	private ProductContentResult toCartSnapshotResult(ProductCartSnapshotResponse s) {
		return ProductContentResult.newBuilder().setCartSnapshot(ProductCartSnapshotMessage.newBuilder()
			.setProductId(s.productId().toString())
			.setSellerId(s.sellerId().toString())
			.setSellerNickname(s.sellerNickname() != null ? s.sellerNickname() : "")
			.setTitle(s.productTitle())
			.setProductType(s.productType() != null ? s.productType() : "")
			.setAmount(s.productAmount())
			.setThumbnailUrl(s.thumbnailUrl() != null ? s.thumbnailUrl() : "")
			.build()).build();
	}

	private PurchasedProductContent toPurchasedContent(ProductContentResponse result) {
		return PurchasedProductContent.newBuilder()
			.setProductId(result.productId().toString())
			.setContent(result.content() != null ? result.content() : "")
			.build();
	}

	private List<UUID> requireBatchProductIds(GetProductContentRequest request) {
		if (!request.getProductId().isBlank()) {
			throw new IllegalArgumentException("purpose=" + request.getPurpose() + "에는 product_id를 채울 수 없습니다.");
		}
		if (request.getProductIdsCount() == 0) {
			throw new IllegalArgumentException("purpose=" + request.getPurpose() + "에는 product_ids가 1개 이상 필요합니다.");
		}
		return request.getProductIdsList().stream().map(this::parseUuid).toList();
	}

	private UUID requireSingleProductId(GetProductContentRequest request) {
		if (request.getProductIdsCount() > 0) {
			throw new IllegalArgumentException("purpose=" + request.getPurpose() + "에는 product_ids를 채울 수 없습니다.");
		}
		if (request.getProductId().isBlank()) {
			throw new IllegalArgumentException("product_id가 필요합니다.");
		}
		return parseUuid(request.getProductId());
	}

	private UUID parseUuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("올바르지 않은 productId 형식입니다: " + value, e);
		}
	}
}
