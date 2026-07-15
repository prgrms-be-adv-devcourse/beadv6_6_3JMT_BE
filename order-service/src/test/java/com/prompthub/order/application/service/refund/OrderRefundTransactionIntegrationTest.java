package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.usecase.OrderRefundUseCase;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.infra.messaging.kafka.consumer.payment.PaymentEventConsumer;
import com.prompthub.order.infra.messaging.kafka.consumer.product.ProductEventConsumer;
import com.prompthub.order.infra.messaging.kafka.event.OrderEventType;
import com.prompthub.order.infra.persistence.order.OrderPaymentPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import com.prompthub.order.infra.persistence.refund.OrderRefundPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@ActiveProfiles("test")
class OrderRefundTransactionIntegrationTest {

	@Autowired
	private OrderRefundUseCase orderRefundUseCase;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private OrderPaymentPersistence orderPaymentPersistence;

	@Autowired
	private OrderRefundPersistence orderRefundPersistence;

	@Autowired
	private OutboxEventPersistence outboxEventPersistence;

	@MockitoSpyBean
	private OutboxEventAppender outboxEventAppender;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private SellerClient sellerClient;

	@MockitoBean
	private PaymentEventConsumer paymentEventConsumer;

	@MockitoBean
	private ProductEventConsumer productEventConsumer;

	@BeforeEach
	void cleanUp() {
		orderRefundPersistence.deleteAll();
		outboxEventPersistence.deleteAll();
		orderPaymentPersistence.deleteAll();
		orderPersistence.deleteAll();
	}

	@Test
	@DisplayName("환불 이력, 주문·상품 상태와 요청 Outbox를 한 트랜잭션으로 저장한다")
	void requestRefund_success_commitsAllChanges() {
		Order order = savePaidOrderAndPayment();
		OrderProduct target = order.getOrderProducts().getFirst();

		orderRefundUseCase.requestRefund(BUYER_ID, order.getId(), PAYMENT_ID, target.getId());

		Order savedOrder = orderPersistence.findByIdWithOrderProducts(order.getId()).orElseThrow();
		OrderProduct savedTarget = findProduct(savedOrder, target.getId());
		OrderRefund savedRefund = orderRefundPersistence
			.findByPaymentIdAndOrderProductId(PAYMENT_ID, target.getId())
			.orElseThrow();

		assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
		assertThat(savedTarget.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
		assertThat(savedRefund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
		assertThat(savedRefund.getTotalRefundAmount()).isEqualTo(target.getProductAmount());
		assertThat(savedRefund.getNextCheckAt()).isEqualTo(savedRefund.getRequestedAt().plusMinutes(10));
		assertThat(outboxEventPersistence.findAll())
			.singleElement()
			.satisfies(outbox -> {
				assertThat(outbox.getOrderId()).isEqualTo(order.getId());
				assertThat(outbox.getEventType()).isEqualTo(OrderEventType.ORDER_REFUND_REQUESTED.code());
				assertThat(outbox.getPayload()).contains("\"orderProductId\":\"" + target.getId() + "\"");
			});
	}

	@Test
	@DisplayName("상태 변경 뒤 낙관적 잠금 예외가 발생하면 환불 이력과 주문·상품 상태를 모두 롤백한다")
	void requestRefund_optimisticLockFailure_rollsBackAllChanges() {
		Order order = savePaidOrderAndPayment();
		UUID targetId = order.getOrderProducts().getFirst().getId();
		doThrow(new ObjectOptimisticLockingFailureException(Order.class, order.getId()))
			.when(outboxEventAppender)
			.append(any());

		assertThatThrownBy(() ->
			orderRefundUseCase.requestRefund(BUYER_ID, order.getId(), PAYMENT_ID, targetId)
		).isInstanceOf(ObjectOptimisticLockingFailureException.class);

		Order savedOrder = orderPersistence.findByIdWithOrderProducts(order.getId()).orElseThrow();
		OrderProduct savedTarget = findProduct(savedOrder, targetId);

		assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(savedTarget.getOrderStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(orderRefundPersistence.count()).isZero();
		assertThat(outboxEventPersistence.count()).isZero();
	}

	private Order savePaidOrderAndPayment() {
		Order order = createPaidOrderWithProducts();
		Order savedOrder = orderPersistence.saveAndFlush(order);
		orderPaymentPersistence.saveAndFlush(OrderPayment.create(
			savedOrder.getId(),
			PAYMENT_ID,
			BUYER_ID,
			savedOrder.getTotalOrderAmount(),
			APPROVED_AT
		));
		return savedOrder;
	}

	private OrderProduct findProduct(Order order, UUID orderProductId) {
		return order.getOrderProducts().stream()
			.filter(product -> product.getId().equals(orderProductId))
			.findFirst()
			.orElseThrow();
	}
}
