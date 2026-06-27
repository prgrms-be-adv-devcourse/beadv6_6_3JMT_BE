package com.prompthub.order.infra.grpc.client.seller;

import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.grpc.seller.SellerBatchQueryRequest;
import com.prompthub.order.grpc.seller.SellerBatchQueryResponse;
import com.prompthub.order.grpc.seller.SellerInfo;
import com.prompthub.order.grpc.seller.SellerQueryServiceGrpc;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile({"dev", "prod"})
public class SellerGrpcClientAdapter implements SellerClient {

	private final SellerQueryServiceGrpc.SellerQueryServiceBlockingStub stub;
	private final int deadlineMs;

	public SellerGrpcClientAdapter(
		SellerQueryServiceGrpc.SellerQueryServiceBlockingStub stub,
		@Value("${prompthub.grpc.seller.deadline-ms:2000}") int deadlineMs
	) {
		this.stub = stub;
		this.deadlineMs = deadlineMs;
	}

	@Override
	public Map<UUID, String> getSellerNicknames(List<UUID> sellerIds) {
		if (sellerIds.isEmpty()) {
			return Map.of();
		}

		SellerBatchQueryRequest request = SellerBatchQueryRequest.newBuilder()
			.addAllSellerIds(sellerIds.stream().map(UUID::toString).toList())
			.build();

		try {
			SellerBatchQueryResponse response = withDeadline().findSellers(request);
			return response.getSellersList().stream()
				.filter(seller -> !seller.getSellerName().isEmpty())
				.collect(Collectors.toMap(
					seller -> UUID.fromString(seller.getSellerId()),
					SellerInfo::getSellerName,
					(existing, ignored) -> existing
				));
		} catch (StatusRuntimeException exception) {
			log.warn("판매자 닉네임 gRPC 조회에 실패해 빈 결과로 대체합니다. sellerIds={}", sellerIds, exception);
			return Map.of();
		}
	}

	private SellerQueryServiceGrpc.SellerQueryServiceBlockingStub withDeadline() {
		return stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
	}
}
