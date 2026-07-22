package com.prompthub.product.infra.messaging.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.prompthub.product.domain.model.entity.OutboxEvent;
import com.prompthub.product.domain.model.enums.OutboxEventStatus;
import com.prompthub.product.domain.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

	private static final OutboxRelayProperties PROPERTIES = new OutboxRelayProperties(true, 5000L, 100, 3, "product-events");

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Mock
	private KafkaTemplate<String, String> stringKafkaTemplate;

	private OutboxRelay outboxRelay;

	@BeforeEach
	void setUp() {
		outboxRelay = new OutboxRelay(outboxEventRepository, stringKafkaTemplate, PROPERTIES);
	}

	@Test
	void publishPendingEvents_성공하면_PUBLISHED로_전이한다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());
		given(outboxEventRepository.findPendingEvents(100)).willReturn(List.of(event));
		given(stringKafkaTemplate.send(eq("product-events"), any(), any()))
			.willReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

		outboxRelay.publishPendingEvents();

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		verify(stringKafkaTemplate).send("product-events", event.getAggregateId().toString(), event.getPayload());
	}

	@Test
	void publishPendingEvents_실패하면_재시도횟수만_증가하고_PENDING을_유지한다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());
		given(outboxEventRepository.findPendingEvents(100)).willReturn(List.of(event));
		CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("kafka down"));
		given(stringKafkaTemplate.send(eq("product-events"), any(), any())).willReturn(failed);

		outboxRelay.publishPendingEvents();

		assertThat(event.getRetryCount()).isEqualTo(1);
		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
	}

	@Test
	void publishPendingEvents_최대재시도_도달하면_FAILED로_전이한다() {
		OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_ON_SALE_CHANGED", "{}", LocalDateTime.now());
		event.recordPublishFailure(3);
		event.recordPublishFailure(3);
		given(outboxEventRepository.findPendingEvents(100)).willReturn(List.of(event));
		CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("kafka down"));
		given(stringKafkaTemplate.send(eq("product-events"), any(), any())).willReturn(failed);

		outboxRelay.publishPendingEvents();

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
	}
}
