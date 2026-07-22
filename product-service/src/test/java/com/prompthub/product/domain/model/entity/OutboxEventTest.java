package com.prompthub.product.domain.model.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.enums.OutboxEventStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

	@Test
	void create_생성하면_PENDING_상태다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(event.getRetryCount()).isZero();
	}

	@Test
	void markPublished_호출하면_PUBLISHED로_전이하고_시각을_남긴다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());
		LocalDateTime publishedAt = LocalDateTime.now();

		event.markPublished(publishedAt);

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
	}

	@Test
	void recordPublishFailure_최대횟수_미만이면_PENDING을_유지한다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());

		event.recordPublishFailure(3);

		assertThat(event.getRetryCount()).isEqualTo(1);
		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
	}

	@Test
	void recordPublishFailure_최대횟수에_도달하면_FAILED로_전이한다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());

		event.recordPublishFailure(1);

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
	}
}
