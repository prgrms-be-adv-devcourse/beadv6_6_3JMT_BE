package com.prompthub.product.infra.grpc;

import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.user.grpc.product.GetProductsByIdsRequest;
import com.prompthub.user.grpc.product.GetProductsByIdsResponse;
import com.prompthub.user.grpc.product.Product;
import com.prompthub.user.grpc.product.ProductServiceGrpc;
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
public class UserProductGrpcService extends ProductServiceGrpc.ProductServiceImplBase {

	private final ProductInternalUseCase productInternalUseCase;

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
					.setCategory(p.category() != null ? p.category() : "")
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
