package com.prompthub.order.infra.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.prompthub.order.application.service.order.OrderExpirationMetrics.CandidateSource.DB;
import static com.prompthub.order.application.service.order.OrderExpirationMetrics.CompensationOutcome.DLQ;
import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.EVENT_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static org.assertj.core.api.Assertions.assertThat;

class MicrometerOrderExpirationMetricsTest {

	@Test
	void recordsDatabaseCandidatesAndDlqWithoutIdentifierTags() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		MicrometerOrderExpirationMetrics metrics =
			new MicrometerOrderExpirationMetrics(registry);

		metrics.recordCandidates(DB, 3);
		metrics.recordCompensation(DLQ);

		assertThat(registry.get("order.expiration.candidates")
			.tag("source", "db")
			.counter()
			.count()).isEqualTo(3);
		assertThat(registry.get("order.expiration.compensation")
			.tag("outcome", "dlq")
			.counter()
			.count()).isEqualTo(1);
		assertNoIdentifierTags(registry);
	}

	private void assertNoIdentifierTags(SimpleMeterRegistry registry) {
		List<Tag> tags = registry.getMeters().stream()
			.flatMap(meter -> meter.getId().getTags().stream())
			.toList();
		assertThat(tags)
			.extracting(Tag::getKey)
			.doesNotContain("buyerId", "productId", "orderId", "eventId");
		assertThat(tags)
			.extracting(Tag::getValue)
			.doesNotContain(
				"buyerId",
				"productId",
				"orderId",
				"eventId",
				BUYER_ID.toString(),
				PRODUCT_ID_1.toString(),
				ORDER_ID.toString(),
				EVENT_ID.toString()
			);
	}
}
