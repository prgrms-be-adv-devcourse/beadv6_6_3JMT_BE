package com.prompthub.order.infra.messaging.kafka.producer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRelayPropertiesTest {

	@Test
	void rejectsNonPositiveRelayValues() {
		assertThatThrownBy(() -> new OutboxRelayProperties(true, 0L, 100, 10_000L, 30_000L, "order-events"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OutboxRelayProperties(true, 5_000L, 0, 10_000L, 30_000L, "order-events"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OutboxRelayProperties(true, 5_000L, 100, 0L, 30_000L, "order-events"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsBlankTopicAndLeaseNoLongerThanTimeout() {
		assertThatThrownBy(() -> new OutboxRelayProperties(true, 5_000L, 100, 10_000L, 30_000L, " "))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OutboxRelayProperties(true, 5_000L, 100, 10_000L, 10_000L, "order-events"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsInvalidRetryBounds() {
		assertThatThrownBy(() -> new OutboxRetryProperties(0L, 300_000L, 10))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OutboxRetryProperties(5_000L, 4_999L, 10))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OutboxRetryProperties(5_000L, 300_000L, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
