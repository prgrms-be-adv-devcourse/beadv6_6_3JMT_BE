package com.prompthub.product.infra.client;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.SellerInfo;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"default", "test"})
public class StubSellerClient implements SellerClient {

	private static final String STUB_SELLER_NAME = "테스트판매자";
	private static final String STUB_STATUS = "ACTIVE";

	@Override
	public SellerInfo getSellerInfo(UUID userId) {
		return new SellerInfo(userId, STUB_SELLER_NAME, null, STUB_STATUS);
	}
}
