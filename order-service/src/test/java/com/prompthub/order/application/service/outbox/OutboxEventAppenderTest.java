package com.prompthub.order.application.service.outbox;

import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.createPaymentApprovedEvent;
import static com.prompthub.order.fixture.OrderFixture.createPendingOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OutboxEventAppenderTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Test
	@DisplayName("ORDER_PAID payload를 JSON으로 직렬화해 OutboxEvent를 저장한다")
	void appendOrderPaid_savesSerializedOutboxEvent() throws Exception {
		Order order = createPendingOrderWithProducts();
		PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId());
		OutboxEventAppender appender = new OutboxEventAppender(objectMapper, outboxEventRepository);

		appender.appendOrderPaid(order, event);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		then(outboxEventRepository).should().save(captor.capture());

		OutboxEvent saved = captor.getValue();
		assertThat(saved.getAggregateId()).isEqualTo(order.getId());
		assertThat(saved.getAggregateType()).isEqualTo("ORDER");
		assertThat(saved.getEventType()).isEqualTo("ORDER_PAID");
		assertThat(saved.getTopic()).isEqualTo("order-events");
		assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(saved.getRetryCount()).isZero();
		assertThat(saved.getOccurredAt()).isEqualTo(APPROVED_AT);
		assertThat(saved.getPublishedAt()).isNull();

		JsonNode payload = objectMapper.readTree(saved.getPayload());
		assertThat(payload.path("orderId").stringValue()).isEqualTo(order.getId().toString());
		assertThat(payload.path("buyerId").stringValue()).isEqualTo(BUYER_ID.toString());
		assertThat(payload.path("paymentId").stringValue()).isEqualTo(PAYMENT_ID.toString());
		assertThat(payload.path("totalAmount").intValue()).isEqualTo(TOTAL_AMOUNT);
		assertThat(LocalDateTime.parse(payload.path("paidAt").stringValue())).isEqualTo(APPROVED_AT);
		assertThat(payload.path("orderProductIds")).hasSize(order.getOrderProducts().size());
	}
}
