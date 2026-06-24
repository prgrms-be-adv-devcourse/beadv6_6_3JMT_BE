package com.prompthub.product.infra.client;

import com.prompthub.product.application.client.SellerInfo;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-service", path = "/internal/sellers")
public interface SellerFeignClient {

	@GetMapping("/{userId}")
	SellerInfo getSellerInfo(@PathVariable UUID userId);
}
