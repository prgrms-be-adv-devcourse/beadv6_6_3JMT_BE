package com.prompthub.order.infra.grpc.client.stub;

import com.prompthub.order.application.client.SellerClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class StubSellerClient implements SellerClient {

	@Override
	public Map<UUID, String> getSellerNicknames(List<UUID> sellerIds) {
		return Map.of();
	}
}
