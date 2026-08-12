package com.prompthub.product.infra.messaging.producer;

import com.prompthub.common.event.EventMessage;
import com.prompthub.product.application.usecase.inspection.ProductEventPublisher;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.infra.messaging.producer.event.ProductChangedPayload;
import com.prompthub.product.infra.messaging.producer.event.ProductDeletedPayload;
import com.prompthub.product.infra.messaging.producer.event.ProductPriceChangedPayload;
import com.prompthub.product.infra.messaging.producer.event.ProductReviewRequestedPayload;
import com.prompthub.product.infra.messaging.producer.event.ProductStoppedPayload;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * product-events 발행 어댑터. (루트 kafka-event.md 참고)
 * 모든 이벤트를 공통 {@link EventMessage} 로 감싸 발행한다. eventType 은 {@link ProductEventType}.code()(UPPER_SNAKE),
 * aggregateType 은 "PRODUCT", Kafka key = aggregateId = productId. 도메인 상태 변경 트랜잭션 커밋 후(AFTER_COMMIT) 발행한다.
 *
 * <p>send()가 돌려주는 future의 완료 여부를 관측한다(2026-08-05 로드맵 PR4) — broker 전송이
 * 비동기로 실패해도 아무도 안 보면 이벤트가 조용히 사라진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventProducer implements ProductEventPublisher {

	private static final String TOPIC = "product-events";
	private static final String AGGREGATE_TYPE = "PRODUCT";

	private final KafkaTemplate<String, Object> kafkaTemplate;

	@Override
	public void publishStopped(UUID productId) {
		publish(ProductEventType.PRODUCT_STOPPED, productId, ProductStoppedPayload.of(productId));
	}

	@Override
	public void publishDeleted(UUID productId) {
		publish(ProductEventType.PRODUCT_DELETED, productId, ProductDeletedPayload.of(productId));
	}

	@Override
	public void publishPriceChanged(UUID productId, int previousPrice, int changedPrice) {
		publish(ProductEventType.PRODUCT_PRICE_CHANGED, productId,
			ProductPriceChangedPayload.of(productId, previousPrice, changedPrice));
	}

	@Override
	public void publishProductChanged(UUID familyRootId) {
		publish(ProductEventType.PRODUCT_CHANGED, familyRootId, ProductChangedPayload.of(familyRootId));
	}

	@Override
	public void publishReviewRequested(
		Product product, UUID duplicateOfProductId, String presignedThumbnailUrl, List<String> presignedImageUrls
	) {
		publish(ProductEventType.PRODUCT_REVIEW_REQUESTED, product.getId(),
			ProductReviewRequestedPayload.of(
				product.getId(),
				product.getProductType().name(),
				product.getName(),
				product.getDescription(),
				product.getContent(),
				product.getTags(),
				presignedThumbnailUrl,
				presignedImageUrls,
				duplicateOfProductId,
				product.getAmountType() == AmountType.FREE));
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
	// 두 경로 모두 같은 sendAndObserve()를 거쳐야 완료 콜백이 빠짐없이 붙는다.
	private void send(UUID aggregateId, EventMessage<Object> message) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					sendAndObserve(aggregateId, message);
				}
			});
		} else {
			sendAndObserve(aggregateId, message);
		}
	}

	// producer 내부 retry 설정에 기대어 성공했다고 간주하지 않는다 — future가 최종 실패하면
	// 여기 error 로그가 남아야 한다. future가 null(테스트 더블 등)이어도 조용히 넘어간다.
	private void sendAndObserve(UUID aggregateId, EventMessage<Object> message) {
		var future = kafkaTemplate.send(TOPIC, aggregateId.toString(), message);
		if (future == null) {
			return;
		}
		future.whenComplete((result, ex) -> logSendResult(aggregateId, message, result, ex));
	}

	// payload·presigned URL은 남기지 않는다 — 원인 추적에 필요한 식별자만 로그로 남긴다.
	private void logSendResult(UUID aggregateId, EventMessage<Object> message, SendResult<String, Object> result, Throwable ex) {
		if (ex != null) {
			log.error("product event 발행 실패. eventId={}, eventType={}, aggregateId={}, topic={}",
				message.eventId(), message.eventType(), aggregateId, TOPIC, ex);
			return;
		}
		RecordMetadata metadata = result != null ? result.getRecordMetadata() : null;
		log.debug("product event 발행 성공. eventId={}, eventType={}, aggregateId={}, topic={}, partition={}, offset={}",
			message.eventId(), message.eventType(), aggregateId, TOPIC,
			metadata != null ? metadata.partition() : null,
			metadata != null ? metadata.offset() : null);
	}
}
