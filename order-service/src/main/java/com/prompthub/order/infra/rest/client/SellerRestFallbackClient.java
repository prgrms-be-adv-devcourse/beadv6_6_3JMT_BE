package com.prompthub.order.infra.rest.client;

import com.prompthub.order.application.client.SellerClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * local/default 프로파일에서 사용하는 SellerClient 구현체.
 * user-service에 seller 닉네임 batch REST 엔드포인트가 없으므로,
 * 조회 실패 시 빈 맵을 반환하여 AdminOrderService의 fallback("알 수 없음")을 활용합니다.
 */
@Slf4j
@Component
@Profile({"default", "local"})
public class SellerRestFallbackClient implements SellerClient {

	@Override
	public Map<UUID, String> getSellerNicknames(List<UUID> sellerIds) {
		log.debug("local 프로파일에서는 판매자 닉네임을 조회하지 않습니다. sellerIds={}", sellerIds);
		return Map.of();
	}
}
