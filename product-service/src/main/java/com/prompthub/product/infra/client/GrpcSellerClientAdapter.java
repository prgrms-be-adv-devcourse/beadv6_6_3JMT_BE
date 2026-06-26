package com.prompthub.product.infra.client;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.SellerInfo;
import com.prompthub.product.grpc.seller.SellerQueryRequest;
import com.prompthub.product.grpc.seller.SellerQueryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile({"prod", "local"})
public class GrpcSellerClientAdapter implements SellerClient {

	private final SellerQueryServiceGrpc.SellerQueryServiceBlockingStub stub;

	public GrpcSellerClientAdapter(ManagedChannel userServiceGrpcChannel) {
		this.stub = SellerQueryServiceGrpc.newBlockingStub(userServiceGrpcChannel);
	}

	@Override
	public SellerInfo getSellerInfo(UUID userId) {
		try {
			com.prompthub.product.grpc.seller.SellerInfo result = stub.getSeller(
				SellerQueryRequest.newBuilder()
					.setSellerId(userId.toString())
					.build()
			);
			return new SellerInfo(
				UUID.fromString(result.getSellerId()),
				result.getSellerName(),
				result.getProfileImageUrl().isBlank() ? null : result.getProfileImageUrl(),
				result.getStatus()
			);
		} catch (StatusRuntimeException e) {
			log.error("gRPC GetSeller failed: userId={}, status={}", userId, e.getStatus(), e);
			throw new RuntimeException("판매자 정보 조회에 실패했습니다: " + userId, e);
		}
	}
}
