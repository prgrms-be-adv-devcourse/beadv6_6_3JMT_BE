package com.prompthub.order.application.service.event;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.CartProduct;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import com.prompthub.order.domain.repository.ProcessedEventRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import com.prompthub.order.infra.persistence.cart.CartPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT_OFFSET;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.OTHER_BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.SELLER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.approvedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static com.prompthub.order.fixture.PaymentEventFixture.failedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.productIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;

@SpringBootTest
@ActiveProfiles("test")
class PaymentEventTransactionIntegrationTest {

	private static final UUID UNRELATED_PRODUCT =
		UUID.fromString("00000000-0000-0000-0000-000000000799");
	private static final LocalDateTime REFUNDED_AT = LocalDateTime.of(2026, 7, 17, 11, 0);
	private static final String REFUNDED_AT_OFFSET = "2026-07-17T11:00:00+09:00";

	@Autowired
	private PaymentApprovedProcessor approvedProcessor;

	@Autowired
	private PaymentFailedProcessor failedProcessor;

	@Autowired
	private PaymentRefundedProcessor refundedProcessor;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private CartPersistence cartPersistence;

	@Autowired
	private OutboxEventPersistence outboxEventPersistence;

	@Autowired
	private ObjectMapper objectMapper;

	@PersistenceContext
	private EntityManager entityManager;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private SellerClient sellerClient;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	@MockitoSpyBean
	private OutboxEventRepository outboxEventRepository;

	@MockitoSpyBean
	private ProcessedEventRepository processedEventRepository;

	@AfterEach
	void tearDown() {
		reset(outboxEventRepository, processedEventRepository, orderExpirationStore);
		processedEventRepository.deleteAll();
		outboxEventPersistence.deleteAll();
		orderPersistence.deleteAll();
		cartPersistence.deleteAll();
	}

	@Test
	void approvedEvent_commitsFourProductsCartOneOutboxProcessedEventAndAfterCommitCleanup() {
		Order order = saveScenario();
		UUID eventId = UUID.randomUUID();

		approvedProcessor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(order));

		Order reloaded = reloadOrder();
		assertThat(reloaded.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(reloaded.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		assertThat(cartProductIds()).containsExactly(UNRELATED_PRODUCT);
		assertThat(outboxEventPersistence.findAll())
			.extracting(OutboxEvent::getAggregateId)
			.containsExactly(ORDER_A);
		assertThat(processedEventRepository.count()).isEqualTo(1);
		then(orderExpirationStore).should().removeExpiration(ORDER_A);
		then(orderExpirationStore).should().clearRetryCount(ORDER_A);
	}

	@Test
	void approvedEvent_outboxFailure_rollsBackOrderCartOutboxAndProcessedEventAndSkipsRedisCleanup() {
		Order order = saveScenario();
		willThrow(new RuntimeException("outbox failure"))
			.given(outboxEventRepository).save(any());

		assertThatThrownBy(() -> approvedProcessor.process(
			UUID.randomUUID(),
			"PAYMENT_APPROVED",
			APPROVED_AT,
			approvedPayload(order)
		))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("outbox failure");

		entityManager.clear();
		assertCreatedStateAndNoSideEffects();
		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	@Test
	void approvedEvent_processedEventFailure_rollsBackOrderCartOutboxAndSkipsRedisCleanup() {
		Order order = saveScenario();
		willThrow(new RuntimeException("processed event failure"))
			.given(processedEventRepository).save(any());

		assertThatThrownBy(() -> approvedProcessor.process(
			UUID.randomUUID(),
			"PAYMENT_APPROVED",
			APPROVED_AT,
			approvedPayload(order)
		))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("processed event failure");

		entityManager.clear();
		assertCreatedStateAndNoSideEffects();
		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	@Test
	void sameApprovedEventTwice_keepsOneProcessedEventAndOneOutbox() {
		Order order = saveScenario();
		UUID eventId = UUID.randomUUID();
		PaymentApprovedPayload payload = approvedPayload(order);

		approvedProcessor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);
		approvedProcessor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);

		assertThat(outboxEventPersistence.count()).isEqualTo(1);
		assertThat(processedEventRepository.count()).isEqualTo(1);
		then(orderExpirationStore).should(times(2)).removeExpiration(ORDER_A);
	}

	@Test
	void differentLateApproval_marksProcessedCleansExpirationAndPreservesReaddedCartProduct() {
		Order order = saveScenario();
		PaymentApprovedPayload payload = approvedPayload(order);
		approvedProcessor.process(UUID.randomUUID(), "PAYMENT_APPROVED", APPROVED_AT, payload);
		Cart cart = cartPersistence.findByBuyerIdWithCartProducts(BUYER_ID).orElseThrow();
		cart.addProduct(PRODUCT_A);
		cartPersistence.saveAndFlush(cart);

		approvedProcessor.process(UUID.randomUUID(), "PAYMENT_APPROVED", APPROVED_AT, payload);

		assertThat(reloadOrder().getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(cartProductIds()).containsExactly(PRODUCT_A, UNRELATED_PRODUCT);
		assertThat(outboxEventPersistence.count()).isEqualTo(1);
		assertThat(processedEventRepository.count()).isEqualTo(2);
		then(orderExpirationStore).should(times(2)).removeExpiration(ORDER_A);
		then(orderExpirationStore).should(times(2)).clearRetryCount(ORDER_A);
	}

	@Test
	void approvedEvent_amountMismatch_rollsBackWithoutSideEffects() {
		Order order = saveScenario();
		PaymentApprovedPayload payload = new PaymentApprovedPayload(
			PAYMENT_ID,
			ORDER_A,
			BUYER_ID,
			order.getTotalOrderAmount() - 1,
			APPROVED_AT_OFFSET
		);

		assertThatThrownBy(() -> approvedProcessor.process(
			UUID.randomUUID(),
			"PAYMENT_APPROVED",
			APPROVED_AT,
			payload
		))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);

		entityManager.clear();
		assertCreatedStateAndNoSideEffects();
		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	@Test
	void approvedEvent_buyerMismatch_rollsBackWithoutSideEffects() {
		Order order = saveScenario();
		PaymentApprovedPayload payload = new PaymentApprovedPayload(
			PAYMENT_ID,
			ORDER_A,
			OTHER_BUYER_ID,
			order.getTotalOrderAmount(),
			APPROVED_AT_OFFSET
		);

		assertThatThrownBy(() -> approvedProcessor.process(
			UUID.randomUUID(),
			"PAYMENT_APPROVED",
			APPROVED_AT,
			payload
		))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_ACCESS_DENIED);

		entityManager.clear();
		assertCreatedStateAndNoSideEffects();
		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	@Test
	void refundedEvent_commitsOnlyTargetProductSingleProductOutboxAndProcessedEvent() throws Exception {
		Order paidOrder = saveAndApproveScenario();

		refundedProcessor.process(
			UUID.randomUUID(),
			"PAYMENT_REFUNDED",
			REFUNDED_AT,
			refundedPayload(paidOrder, ORDER_PRODUCT_A, BUYER_ID, 10_000)
		);

		Order reloaded = reloadOrder();
		assertThat(reloaded.getOrderStatus()).isEqualTo(OrderStatus.PARTIAL_REFUNDED);
		assertThat(reloaded.getRefundedAt()).isNull();
		assertThat(findProduct(reloaded, ORDER_PRODUCT_A).getOrderStatus())
			.isEqualTo(OrderProductStatus.REFUNDED);
		assertThat(reloaded.getOrderProducts())
			.filteredOn(product -> !product.getId().equals(ORDER_PRODUCT_A))
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		OutboxEvent refundOutbox = refundOutboxes().getFirst();
		JsonNode refundMessage = objectMapper.readTree(refundOutbox.getPayload());
		JsonNode products = refundMessage.path("payload").path("products");
		assertThat(products.size()).isEqualTo(1);
		assertThat(products.get(0).path("orderProductId").asText()).isEqualTo(ORDER_PRODUCT_A.toString());
		assertThat(products.get(0).path("sellerId").asText()).isEqualTo(SELLER_A.toString());
		assertThat(processedEventRepository.count()).isEqualTo(2);
	}

	@Test
	void sameRefundedEventTwice_keepsOneRefundOutboxAndOneRefundProcessedEvent() {
		Order paidOrder = saveAndApproveScenario();
		UUID eventId = UUID.randomUUID();
		PaymentRefundedPayload payload = refundedPayload(paidOrder, ORDER_PRODUCT_A, BUYER_ID, 10_000);

		refundedProcessor.process(eventId, "PAYMENT_REFUNDED", REFUNDED_AT, payload);
		refundedProcessor.process(eventId, "PAYMENT_REFUNDED", REFUNDED_AT, payload);

		assertThat(refundOutboxes()).hasSize(1);
		assertThat(processedEventRepository.count()).isEqualTo(2);
	}

	@Test
	void semanticDuplicateRefundWithDifferentEventId_marksProcessedWithoutSecondOutbox() {
		Order paidOrder = saveAndApproveScenario();
		PaymentRefundedPayload payload = refundedPayload(paidOrder, ORDER_PRODUCT_A, BUYER_ID, 10_000);

		refundedProcessor.process(UUID.randomUUID(), "PAYMENT_REFUNDED", REFUNDED_AT, payload);
		refundedProcessor.process(UUID.randomUUID(), "PAYMENT_REFUNDED", REFUNDED_AT.plusMinutes(1), payload);

		assertThat(reloadOrder().getOrderStatus()).isEqualTo(OrderStatus.PARTIAL_REFUNDED);
		assertThat(refundOutboxes()).hasSize(1);
		assertThat(processedEventRepository.count()).isEqualTo(3);
	}

	@Test
	void refundedEvent_buyerMismatch_rollsBackWithoutRefundSideEffects() {
		Order paidOrder = saveAndApproveScenario();

		assertThatThrownBy(() -> refundedProcessor.process(
			UUID.randomUUID(),
			"PAYMENT_REFUNDED",
			REFUNDED_AT,
			refundedPayload(paidOrder, ORDER_PRODUCT_A, OTHER_BUYER_ID, 10_000)
		))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_ACCESS_DENIED);

		entityManager.clear();
		assertPaidStateAndNoRefundSideEffects();
	}

	@Test
	void refundedEvent_outboxFailure_rollsBackProductOrderOutboxAndProcessedEvent() {
		Order paidOrder = saveAndApproveScenario();
		willThrow(new RuntimeException("refund outbox failure"))
			.given(outboxEventRepository).save(any());

		assertThatThrownBy(() -> refundedProcessor.process(
			UUID.randomUUID(),
			"PAYMENT_REFUNDED",
			REFUNDED_AT,
			refundedPayload(paidOrder, ORDER_PRODUCT_A, BUYER_ID, 10_000)
		))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("refund outbox failure");

		entityManager.clear();
		assertPaidStateAndNoRefundSideEffects();
	}

	@Test
	void refundedEvent_processedEventFailure_rollsBackProductOrderAndOutbox() {
		Order paidOrder = saveAndApproveScenario();
		willThrow(new RuntimeException("refund processed event failure"))
			.given(processedEventRepository).save(any());

		assertThatThrownBy(() -> refundedProcessor.process(
			UUID.randomUUID(),
			"PAYMENT_REFUNDED",
			REFUNDED_AT,
			refundedPayload(paidOrder, ORDER_PRODUCT_A, BUYER_ID, 10_000)
		))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("refund processed event failure");

		entityManager.clear();
		assertPaidStateAndNoRefundSideEffects();
	}

	@Test
	void failedEvent_commitsFailedStatesRestoresCartAndCleansExpiration() {
		saveScenario();

		failedProcessor.process(UUID.randomUUID(), "PAYMENT_FAILED", FAILED_AT, failedPayload());

		Order reloaded = reloadOrder();
		assertThat(reloaded.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		assertThat(reloaded.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.FAILED);
		assertThat(cartProductIds()).containsExactlyElementsOf(allCartProductIds());
		assertThat(outboxEventPersistence.count()).isZero();
		assertThat(processedEventRepository.count()).isEqualTo(1);
		then(orderExpirationStore).should().removeExpiration(ORDER_A);
		then(orderExpirationStore).should().clearRetryCount(ORDER_A);
	}

	@Test
	void failedEvent_afterCompletedOrder_isNoOpExceptProcessedEvent() {
		Order order = saveScenario();
		approvedProcessor.process(UUID.randomUUID(), "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(order));

		failedProcessor.process(UUID.randomUUID(), "PAYMENT_FAILED", FAILED_AT, failedPayload());

		assertThat(reloadOrder().getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(outboxEventPersistence.count()).isEqualTo(1);
		assertThat(processedEventRepository.count()).isEqualTo(2);
		then(orderExpirationStore).should(times(2)).removeExpiration(ORDER_A);
		then(orderExpirationStore).should(times(2)).clearRetryCount(ORDER_A);
	}

	@Test
	void failedEvent_buyerMismatch_rollsBackWithoutProcessedEvent() {
		saveScenario();
		PaymentFailedPayload payload = new PaymentFailedPayload(PAYMENT_ID, ORDER_A, OTHER_BUYER_ID);

		assertThatThrownBy(() -> failedProcessor.process(
			UUID.randomUUID(),
			"PAYMENT_FAILED",
			FAILED_AT,
			payload
		))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_ACCESS_DENIED);

		entityManager.clear();
		assertCreatedStateAndNoSideEffects();
	}

	@Test
	void failedEvent_processedEventFailure_rollsBackOrderAndFourProductStates() {
		saveScenario();
		willThrow(new RuntimeException("processed event failure"))
			.given(processedEventRepository).save(any());

		assertThatThrownBy(() -> failedProcessor.process(
			UUID.randomUUID(),
			"PAYMENT_FAILED",
			FAILED_AT,
			failedPayload()
		))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("processed event failure");

		entityManager.clear();
		assertCreatedStateAndNoSideEffects();
	}

	private Order saveAndApproveScenario() {
		Order order = saveScenario();
		approvedProcessor.process(UUID.randomUUID(), "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(order));
		entityManager.clear();
		return reloadOrder();
	}

	private PaymentRefundedPayload refundedPayload(
		Order order,
		UUID orderProductId,
		UUID userId,
		int amount
	) {
		return new PaymentRefundedPayload(
			PAYMENT_ID,
			order.getId(),
			userId,
			orderProductId,
			amount,
			"PARTIAL_REFUNDED",
			REFUNDED_AT_OFFSET
		);
	}

	private OrderProduct findProduct(Order order, UUID orderProductId) {
		return order.getOrderProducts().stream()
			.filter(product -> product.getId().equals(orderProductId))
			.findFirst()
			.orElseThrow();
	}

	private List<OutboxEvent> refundOutboxes() {
		return outboxEventPersistence.findAll().stream()
			.filter(outbox -> outbox.getEventType().equals("ORDER_REFUND"))
			.toList();
	}

	private void assertPaidStateAndNoRefundSideEffects() {
		Order reloaded = reloadOrder();
		assertThat(reloaded.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(reloaded.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		assertThat(refundOutboxes()).isEmpty();
		assertThat(outboxEventPersistence.count()).isEqualTo(1);
		assertThat(processedEventRepository.count()).isEqualTo(1);
	}

	private Order saveScenario() {
		Order order = orderPersistence.saveAndFlush(createdOrder());
		Cart cart = Cart.create(BUYER_ID);
		productIds().forEach(cart::addProduct);
		cart.addProduct(UNRELATED_PRODUCT);
		cartPersistence.saveAndFlush(cart);
		return order;
	}

	private Order reloadOrder() {
		return orderPersistence.findByIdWithOrderProducts(ORDER_A).orElseThrow();
	}

	private List<UUID> cartProductIds() {
		return cartPersistence.findByBuyerIdWithCartProducts(BUYER_ID).orElseThrow()
			.getCartProducts().stream()
			.map(CartProduct::getProductId)
			.sorted()
			.toList();
	}

	private List<UUID> allCartProductIds() {
		return List.of(
			productIds().get(0),
			productIds().get(1),
			productIds().get(2),
			productIds().get(3),
			UNRELATED_PRODUCT
		).stream().sorted().toList();
	}

	private void assertCreatedStateAndNoSideEffects() {
		Order reloaded = reloadOrder();
		assertThat(reloaded.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(reloaded.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PENDING);
		assertThat(cartProductIds()).containsExactlyElementsOf(allCartProductIds());
		assertThat(outboxEventPersistence.count()).isZero();
		assertThat(processedEventRepository.count()).isZero();
	}
}
