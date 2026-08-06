package com.prompthub.order.infra.messaging.kafka;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import com.prompthub.order.application.client.ProductClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
	"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
	"prompthub.outbox-relay.enabled=true",
	"eureka.client.enabled=false",
	"spring.cloud.discovery.enabled=false"
})
@EmbeddedKafka(
	partitions = 1,
	topics = {"order-events", "order-events.DLT", "payment-events", "payment-events.DLT", "product-events"}
)
@ActiveProfiles("test")
public abstract class KafkaIntegrationTest {

	@MockitoBean
	protected ProductClient productClient;
}
