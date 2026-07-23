package com.prompthub.order.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderExpirationConfigTest {

	@Test
	@DisplayName("주문 만료 Clock은 Asia/Seoul 시간대를 사용한다")
	void clock_usesAsiaSeoul() {
		assertThat(new OrderExpirationConfig().clock().getZone())
			.isEqualTo(ZoneId.of("Asia/Seoul"));
	}
}
