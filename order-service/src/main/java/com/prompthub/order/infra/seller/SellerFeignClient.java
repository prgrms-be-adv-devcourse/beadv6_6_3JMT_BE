package com.prompthub.order.infra.seller;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;
import java.util.UUID;

@FeignClient(name = "user-service", path = "/internal/sellers")
public interface SellerFeignClient {

	@PostMapping("/bulk")
	Map<UUID, String> getSellerNicknames(@RequestBody SellerNicknameRequest request);
}
