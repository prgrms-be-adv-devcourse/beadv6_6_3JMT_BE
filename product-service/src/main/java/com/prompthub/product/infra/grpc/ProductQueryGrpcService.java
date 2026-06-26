package com.prompthub.product.infra.grpc;

import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.grpc.ProductCountRequest;
import com.prompthub.product.grpc.ProductCountResponse;
import com.prompthub.product.grpc.ProductQueryServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductQueryGrpcService extends ProductQueryServiceGrpc.ProductQueryServiceImplBase {

	private final ProductInternalUseCase productInternalUseCase;

	@Override
	public void countBySeller(ProductCountRequest request, StreamObserver<ProductCountResponse> responseObserver) {
		try {
			UUID sellerId = UUID.fromString(request.getSellerId());
			var result = productInternalUseCase.getProductCount(sellerId);
			responseObserver.onNext(ProductCountResponse.newBuilder()
				.setSellerId(result.sellerId().toString())
				.setProductCount((int) result.productCount())
				.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.error("CountBySeller failed: sellerId={}", request.getSellerId(), e);
			responseObserver.onNext(ProductCountResponse.newBuilder()
				.setSellerId(request.getSellerId())
				.setProductCount(0)
				.build());
			responseObserver.onCompleted();
		}
	}
}
