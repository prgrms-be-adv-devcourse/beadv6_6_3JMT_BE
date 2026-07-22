package com.prompthub.product.infra.messaging.producer;

import com.prompthub.common.event.EventMessage;
import com.prompthub.product.infra.messaging.producer.event.ProductDeletedPayload;
import com.prompthub.product.infra.messaging.producer.event.ProductOnSaleChangedPayload;
import com.prompthub.product.infra.messaging.producer.event.ProductPriceChangedPayload;
import com.prompthub.product.infra.messaging.producer.event.ProductStoppedPayload;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * product-events 발행 어댑터. (kafka-event.md §6)
 * 모든 이벤트를 공통 {@link EventMessage} 로 감싸 발행한다. eventType 은 {@link ProductEventType}.code()(UPPER_SNAKE),
 * aggregateType 은 "PRODUCT", Kafka key = aggregateId = productId. 도메인 상태 변경 트랜잭션 커밋 후(AFTER_COMMIT) 발행한다.
 */
@Component
@RequiredArgsConstructor
public class ProductEventProducer {

	private static final String TOPIC = "product-events";
	private static final String AGGREGATE_TYPE = "PRODUCT";

	private final KafkaTemplate<String, Object> kafkaTemplate;

	public void publishStopped(UUID productId) {
		publish(ProductEventType.PRODUCT_STOPPED, productId, ProductStoppedPayload.of(productId));
	}

	public void publishDeleted(UUID productId) {
		publish(ProductEventType.PRODUCT_DELETED, productId, ProductDeletedPayload.of(productId));
	}

	public void publishPriceChanged(UUID productId, int previousPrice, int changedPrice) {
		publish(ProductEventType.PRODUCT_PRICE_CHANGED, productId,
			ProductPriceChangedPayload.of(productId, previousPrice, changedPrice));
	}

	public void publishOnSaleChanged(UUID familyRootId) {
		publish(ProductEventType.PRODUCT_ON_SALE_CHANGED, familyRootId, ProductOnSaleChangedPayload.of(familyRootId));
	}

	private void publish(ProductEventType eventType, UUID aggregateId, Object payload) {
		EventMessage<Object> message = new EventMessage<>(
			UUID.randomUUID(),
			eventType.code(),
			LocalDateTime.now(),
			AGGREGATE_TYPE,
			aggregateId,
			payload
		);
		send(aggregateId, message);
	}

	// 도메인 상태 변경 트랜잭션이 커밋된 후에만 발행한다. 활성 트랜잭션이 없으면 즉시 발행한다.
	private void send(UUID aggregateId, EventMessage<Object> message) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					kafkaTemplate.send(TOPIC, aggregateId.toString(), message);
				}
			});
		} else {
			kafkaTemplate.send(TOPIC, aggregateId.toString(), message);
		}
	}
}
