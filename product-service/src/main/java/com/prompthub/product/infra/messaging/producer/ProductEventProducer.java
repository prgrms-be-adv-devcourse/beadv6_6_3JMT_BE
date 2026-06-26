package com.prompthub.product.infra.messaging.producer;

import com.prompthub.product.infra.messaging.producer.event.ProductDeletedEvent;
import com.prompthub.product.infra.messaging.producer.event.ProductPriceChangedEvent;
import com.prompthub.product.infra.messaging.producer.event.ProductStoppedEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductEventProducer {

	private static final String TOPIC = "product-events";

	private final KafkaTemplate<String, Object> kafkaTemplate;

	public void publishStopped(UUID productId) {
		kafkaTemplate.send(TOPIC, productId.toString(), ProductStoppedEvent.of(productId));
	}

	public void publishDeleted(UUID productId) {
		kafkaTemplate.send(TOPIC, productId.toString(), ProductDeletedEvent.of(productId));
	}

	public void publishPriceChanged(UUID productId, int previousPrice, int changedPrice) {
		kafkaTemplate.send(TOPIC, productId.toString(), ProductPriceChangedEvent.of(productId, previousPrice, changedPrice));
	}
}
