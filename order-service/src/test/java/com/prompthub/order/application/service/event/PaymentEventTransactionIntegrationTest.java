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
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_B;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_B;
import static com.prompthub.order.fixture.PaymentEventFixture.approvedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrders;
import static com.prompthub.order.fixture.PaymentEventFixture.failedPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;

@SpringBootTest
@ActiveProfiles("test")
class PaymentEventTransactionIntegrationTest {

	private static final UUID UNRELATED_PRODUCT =
		UUID.fromString("00000000-0000-0000-0000-000000000799");

	@Autowired
	private PaymentApprovedProcessor approvedProcessor;

	@Autowired
	private PaymentFailedProcessor failedProcessor;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private CartPersistence cartPersistence;

	@Autowired
	private OutboxEventPersistence outboxEventPersistence;

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
	void approvedEvent_commitsOrdersCartOutboxProcessedEventAndAfterCommitCleanup() {
		List<Order> orders = saveScenario();
		UUID eventId = UUID.randomUUID();

		approvedProcessor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(orders));

		assertThat(reloadOrders())
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.COMPLETED);
		assertThat(reloadOrders())
			.flatExtracting(Order::getOrderProducts)
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		assertThat(cartProductIds()).containsExactly(UNRELATED_PRODUCT);
		assertThat(outboxEventPersistence.findAll())
			.extracting(OutboxEvent::getAggregateId)
			.containsExactlyInAnyOrder(ORDER_A, ORDER_B);
		assertThat(processedEventRepository.count()).isEqualTo(1);
		then(orderExpirationStore).should().removeExpiration(ORDER_A);
		then(orderExpirationStore).should().clearRetryCount(ORDER_A);
		then(orderExpirationStore).should().removeExpiration(ORDER_B);
		then(orderExpirationStore).should().clearRetryCount(ORDER_B);
	}

	@Test
	void secondOutboxFailure_rollsBackEveryDatabaseChangeAndSkipsRedisCleanup() {
		List<Order> orders = saveScenario();
		AtomicInteger saves = new AtomicInteger();
		willAnswer(invocation -> {
			if (saves.incrementAndGet() == 2) {
				throw new RuntimeException("second outbox failure");
			}
			return invocation.callRealMethod();
		}).given(outboxEventRepository).save(any());

		assertThatThrownBy(() -> approvedProcessor.process(
			UUID.randomUUID(),
			"PAYMENT_APPROVED",
			APPROVED_AT,
			approvedPayload(orders)
		))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("second outbox failure");

		entityManager.clear();
		assertThat(reloadOrders())
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.CREATED);
		assertThat(cartProductIds())
			.containsExactlyInAnyOrder(PRODUCT_A, PRODUCT_B, UNRELATED_PRODUCT);
		assertThat(outboxEventPersistence.count()).isZero();
		assertThat(processedEventRepository.count()).isZero();
		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	@Test
	void sameApprovedEventTwice_keepsOneProcessedEventAndOneOutboxPerOrder() {
		List<Order> orders = saveScenario();
		UUID eventId = UUID.randomUUID();
		PaymentApprovedPayload payload = approvedPayload(orders);

		approvedProcessor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);
		approvedProcessor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);

		assertThat(outboxEventPersistence.count()).isEqualTo(2);
		assertThat(processedEventRepository.count()).isEqualTo(1);
		then(orderExpirationStore).should(times(1)).removeExpiration(ORDER_A);
		then(orderExpirationStore).should(times(1)).removeExpiration(ORDER_B);
	}

	@Test
	void failedEvent_commitsFailedStatesAndKeepsCartAndOutboxUnchanged() {
		saveScenario();
		UUID eventId = UUID.randomUUID();

		failedProcessor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

		assertThat(reloadOrders())
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.FAILED);
		assertThat(reloadOrders())
			.flatExtracting(Order::getOrderProducts)
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.FAILED);
		assertThat(cartProductIds())
			.containsExactlyInAnyOrder(PRODUCT_A, PRODUCT_B, UNRELATED_PRODUCT);
		assertThat(outboxEventPersistence.count()).isZero();
		assertThat(processedEventRepository.count()).isEqualTo(1);
		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	@Test
	void failedEvent_processedEventFailure_rollsBackAllOrderStatesAndKeepsCart() {
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
		assertThat(reloadOrders())
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.CREATED);
		assertThat(cartProductIds())
			.containsExactlyInAnyOrder(PRODUCT_A, PRODUCT_B, UNRELATED_PRODUCT);
		assertThat(outboxEventPersistence.count()).isZero();
		assertThat(processedEventRepository.count()).isZero();
	}

	private List<Order> saveScenario() {
		List<Order> orders = orderPersistence.saveAllAndFlush(createdOrders());
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_A);
		cart.addProduct(PRODUCT_B);
		cart.addProduct(UNRELATED_PRODUCT);
		cartPersistence.saveAndFlush(cart);
		return orders;
	}

	private List<Order> reloadOrders() {
		return List.of(
			orderPersistence.findByIdWithOrderProducts(ORDER_A).orElseThrow(),
			orderPersistence.findByIdWithOrderProducts(ORDER_B).orElseThrow()
		);
	}

	private List<UUID> cartProductIds() {
		return cartPersistence.findByBuyerIdWithCartProducts(BUYER_ID).orElseThrow()
			.getCartProducts().stream()
			.map(CartProduct::getProductId)
			.sorted()
			.toList();
	}
}
