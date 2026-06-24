package com.prompthub.product.infra.client;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.SellerInfo;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class SellerFeignClientAdapter implements SellerClient {

	private final SellerFeignClient sellerFeignClient;

	@Override
	public SellerInfo getSellerInfo(UUID userId) {
		return sellerFeignClient.getSellerInfo(userId);
	}
}
