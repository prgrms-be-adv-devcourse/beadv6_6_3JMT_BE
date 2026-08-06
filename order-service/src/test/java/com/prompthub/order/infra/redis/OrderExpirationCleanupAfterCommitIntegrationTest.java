package com.prompthub.order.infra.redis;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.event.order.OrderExpirationCleanupRequestedEvent;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.application.service.order.OrderFailureCompensationService;
import com.prompthub.order.application.service.order.OrderProductIdempotencyStore;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.CartProduct;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.ProcessedEventRepository;
import com.prompthub.order.infra.persistence.cart.CartPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static com.prompthub.order.fixture.PaymentEventFixture.failedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.productIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class OrderExpirationCleanupAfterCommitIntegrationTest {

	private static final UUID UNRELATED_PRODUCT =
		UUID.fromString("00000000-0000-0000-0000-000000000799");

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private OrderFailureCompensationService compensationService;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private CartPersistence cartPersistence;

	@Autowired
	private ProcessedEventRepository processedEventRepository;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	@MockitoBean
	private OrderProductIdempotencyStore orderProductIdempotencyStore;

	@AfterEach
	void tearDown() {
		processedEventRepository.deleteAll();
		orderPersistence.deleteAll();
		cartPersistence.deleteAll();
		reset(productClient, orderExpirationStore, orderProductIdempotencyStore);
	}

	@Test
	void cleanupRunsOnlyAfterTransactionCommit() {
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.executeWithoutResult(status -> {
			eventPublisher.publishEvent(new OrderExpirationCleanupRequestedEvent(ORDER_A));
			then(orderExpirationStore).shouldHaveNoInteractions();
		});

		then(orderExpirationStore).should().removeExpiration(ORDER_A);
		then(orderExpirationStore).should().clearRetryCount(ORDER_A);
	}

	@Test
	void rollbackSkipsCleanup() {
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.executeWithoutResult(status -> {
			eventPublisher.publishEvent(new OrderExpirationCleanupRequestedEvent(ORDER_A));
			status.setRollbackOnly();
		});

		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	@Test
	void redisFailureDoesNotRollbackCommittedCompensation() {
		saveScenario();
		willThrow(new RuntimeException("redis unavailable"))
			.given(orderExpirationStore).removeExpiration(ORDER_A);

		assertThatCode(() -> compensationService.compensatePaymentFailure(
			UUID.randomUUID(),
			"PAYMENT_FAILED",
			FAILED_AT,
			failedPayload()
		)).doesNotThrowAnyException();

		Order order = orderPersistence.findByIdWithOrderProducts(ORDER_A).orElseThrow();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.FAILED);
		assertThat(cartProductIds())
			.containsExactlyInAnyOrderElementsOf(expectedRestoredProductIds());
		assertThat(processedEventRepository.count()).isEqualTo(1);
		then(orderExpirationStore).should().removeExpiration(ORDER_A);
		then(orderExpirationStore).should(never()).clearRetryCount(ORDER_A);
	}

	private void saveScenario() {
		orderPersistence.saveAndFlush(createdOrder());
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(UNRELATED_PRODUCT);
		cartPersistence.saveAndFlush(cart);
	}

	private List<UUID> cartProductIds() {
		return cartPersistence.findByBuyerIdWithCartProducts(BUYER_ID).orElseThrow()
			.getCartProducts().stream()
			.map(CartProduct::getProductId)
			.toList();
	}

	private List<UUID> expectedRestoredProductIds() {
		return List.of(
			productIds().get(0),
			productIds().get(1),
			productIds().get(2),
			productIds().get(3),
			UNRELATED_PRODUCT
		);
	}
}
