package com.prompthub.order.infra.messaging.kafka;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
	"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
	"prompthub.outbox-relay.enabled=true"
})
@EmbeddedKafka(
	partitions = 1,
	topics = {"order-events", "payment-events", "product-events"}
)
@ActiveProfiles("test")
public abstract class KafkaIntegrationTest {
}
