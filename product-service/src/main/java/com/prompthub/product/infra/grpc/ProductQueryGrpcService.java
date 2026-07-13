package com.prompthub.product.infra.grpc;

import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.grpc.GetCartSnapshotsRequest;
import com.prompthub.product.grpc.GetCartSnapshotsResponse;
import com.prompthub.product.grpc.GetOrderSnapshotsRequest;
import com.prompthub.product.grpc.GetOrderSnapshotsResponse;
import com.prompthub.product.grpc.GetProductContentRequest;
import com.prompthub.product.grpc.GetProductContentResponse;
import com.prompthub.product.grpc.GetProductsByIdsRequest;
import com.prompthub.product.grpc.GetProductsByIdsResponse;
import com.prompthub.product.grpc.GetSellerStatsRequest;
import com.prompthub.product.grpc.GetSellerStatsResponse;
import com.prompthub.product.grpc.Product;
import com.prompthub.product.grpc.ProductCartSnapshotMessage;
import com.prompthub.product.grpc.ProductOrderSnapshot;
import com.prompthub.product.grpc.ProductQueryServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * product-service가 서버로서 제공하는 gRPC 계약(루트 grpc/product/product_query.proto)의 단일 구현.
 * settlement(GetSellerStats)·order(스냅샷/콘텐츠)·user(GetProductsByIds) 호출을 하나의 서비스로 서빙한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductQueryGrpcService extends ProductQueryServiceGrpc.ProductQueryServiceImplBase {

	private final ProductInternalUseCase productInternalUseCase;

	@Override
	public void getSellerStats(GetSellerStatsRequest request, StreamObserver<GetSellerStatsResponse> responseObserver) {
		try {
			UUID sellerId = UUID.fromString(request.getSellerId());
			var result = productInternalUseCase.getProductCount(sellerId);
			responseObserver.onNext(GetSellerStatsResponse.newBuilder()
				.setSellerId(result.sellerId().toString())
				.setProductCount((int) result.productCount())
				.setSalesCount(result.salesCount())
				.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.error("GetSellerStats failed: sellerId={}", request.getSellerId(), e);
			responseObserver.onNext(GetSellerStatsResponse.newBuilder()
				.setSellerId(request.getSellerId())
				.setProductCount(0)
				.setSalesCount(0)
				.build());
			responseObserver.onCompleted();
		}
	}

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

	@Override
	public void getProductsByIds(GetProductsByIdsRequest request, StreamObserver<GetProductsByIdsResponse> responseObserver) {
		try {
			List<UUID> productIds = request.getProductIdsList().stream()
				.map(UUID::fromString)
				.toList();
			List<Product> products = productInternalUseCase.getProductsByIds(productIds).stream()
				.map(p -> Product.newBuilder()
					.setProductId(p.productId().toString())
					.setSellerId(p.sellerId().toString())
					.setTitle(p.title())
					.setPrice(p.amount())
					.setThumbnailUrl(p.thumbnailUrl() != null ? p.thumbnailUrl() : "")
					.setModel(p.model() != null ? p.model() : "")
					.setSalesCount(p.salesCount())
					.setAverageRating(p.averageRating())
					.setStatus(p.status())
					.build())
				.toList();
			responseObserver.onNext(GetProductsByIdsResponse.newBuilder()
				.addAllProducts(products)
				.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.error("GetProductsByIds failed: productIds={}", request.getProductIdsList(), e);
			responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}
}
