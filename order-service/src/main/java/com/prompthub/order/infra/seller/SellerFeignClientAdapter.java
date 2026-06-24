package com.prompthub.order.infra.seller;

import com.prompthub.order.application.client.SellerClient;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Profile({"dev", "prod"})
@RequiredArgsConstructor
public class SellerFeignClientAdapter implements SellerClient {

	private final SellerFeignClient sellerFeignClient;

	@Override
	public Map<UUID, String> getSellerNicknames(List<UUID> sellerIds) {
		try {
			return sellerFeignClient.getSellerNicknames(new SellerNicknameRequest(sellerIds));
		} catch (FeignException exception) {
			return Map.of();
		}
	}
}
