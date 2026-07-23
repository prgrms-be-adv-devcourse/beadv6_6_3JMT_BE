package com.prompthub.order.application.service.order;

import com.prompthub.exception.BusinessException;
import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductCartSnapshot;
import com.prompthub.order.application.service.cart.CartService;
import com.prompthub.order.application.service.event.PaymentApprovedProcessor;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.ProcessedEventRepository;
import com.prompthub.order.global.exception.CartException;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.persistence.cart.CartPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import com.prompthub.order.presentation.dto.request.AddCartProductRequest;
import com.prompthub.order.support.ConcurrentScenarioRunner;
import com.prompthub.order.support.DatabaseStateProbe;
import com.prompthub.order.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.SELLER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.approvedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static com.prompthub.order.fixture.PaymentEventFixture.failedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.productIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

class OrderFailureCompensationConcurrencyTest extends PostgreSqlIntegrationTestSupport {

	private static final UUID UNRELATED_PRODUCT =
		UUID.fromString("00000000-0000-0000-0000-000000000799");
	private static final long WAIT_SECONDS = 10;

	@Autowired
	private OrderFailureCompensationService compensationService;

	@Autowired
	private PaymentApprovedProcessor approvedProcessor;

	@Autowired
	private CartService cartService;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private CartPersistence cartPersistence;

	@Autowired
	private ProcessedEventRepository processedEventRepository;

	@Autowired
	private OutboxEventPersistence outboxEventPersistence;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private DatabaseStateProbe databaseStateProbe;

	private ConcurrentScenarioRunner concurrentScenarioRunner;

	@BeforeEach
	void setUp() {
		reset(productClient, orderExpirationStore);
		given(productClient.getCartSnapshot(PRODUCT_A)).willReturn(productSnapshot());
		databaseStateProbe = new DatabaseStateProbe(
			orderPersistence,
			cartPersistence,
			jdbcTemplate,
			transactionManager
		);
		concurrentScenarioRunner = new ConcurrentScenarioRunner(transactionManager, WAIT_SECONDS);
	}

	@AfterEach
	void tearDown() {
		concurrentScenarioRunner.close();
		reset(productClient, orderExpirationStore);
	}

	@RepeatedTest(5)
	void concurrentPaymentFailureAndApprovalAlwaysEndsCompletedWithoutPurchasedCartProducts() throws Exception {
		Order order = saveScenario();
		UUID failedEventId = UUID.randomUUID();
		UUID approvedEventId = UUID.randomUUID();

		ConcurrentScenarioRunner.Results results = concurrentScenarioRunner.run(
			() -> compensationService.compensatePaymentFailure(
				failedEventId,
				"PAYMENT_FAILED",
				FAILED_AT,
				failedPayload()
			),
			() -> approvedProcessor.process(
				approvedEventId,
				"PAYMENT_APPROVED",
				APPROVED_AT,
				approvedPayload(order)
			)
		);

		assertThat(results.firstFailure()).isNull();
		assertThat(results.secondFailure()).isNull();
		assertCompletedStateWithoutPurchasedCartProducts();
		assertThat(processedEventRepository.count()).isEqualTo(2);
		assertThat(outboxEventPersistence.count()).isEqualTo(1);
	}

	@RepeatedTest(5)
	void concurrentPaymentFailureAndTimeoutEndsFailedWithSingleCartRows() throws Exception {
		Order order = saveScenario();
		LocalDateTime expiredAt = order.getCreatedAt().plusMinutes(20);

		ConcurrentScenarioRunner.Results results = concurrentScenarioRunner.run(
			() -> compensationService.compensatePaymentFailure(
				UUID.randomUUID(),
				"PAYMENT_FAILED",
				FAILED_AT,
				failedPayload()
			),
			() -> compensationService.compensateTimeout(ORDER_A, expiredAt)
		);

		assertThat(results.firstFailure()).isNull();
		assertThat(results.secondFailure()).isNull();
		assertFailedStateWithSingleRestoredCartRows();
		assertThat(processedEventRepository.count()).isEqualTo(1);
		assertThat(outboxEventPersistence.count()).isZero();
	}

	@RepeatedTest(5)
	void concurrentCartAddAndPaymentFailureKeepsOneProductRow() throws Exception {
		saveScenario();

		ConcurrentScenarioRunner.Results results = concurrentScenarioRunner.run(
			() -> cartService.addCartProduct(BUYER_ID, new AddCartProductRequest(PRODUCT_A)),
			() -> compensationService.compensatePaymentFailure(
				UUID.randomUUID(),
				"PAYMENT_FAILED",
				FAILED_AT,
				failedPayload()
			)
		);

		assertDuplicateOrSuccess(results.firstFailure());
		assertThat(results.secondFailure()).isNull();
		assertFailedStateWithSingleRestoredCartRows();
	}

	private Order saveScenario() {
		Order order = orderPersistence.saveAndFlush(createdOrder());
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(UNRELATED_PRODUCT);
		cartPersistence.saveAndFlush(cart);
		return order;
	}

	private void assertCompletedStateWithoutPurchasedCartProducts() {
		Order order = loadOrder();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		assertThat(cartProductIds()).containsExactly(UNRELATED_PRODUCT);
	}

	private void assertFailedStateWithSingleRestoredCartRows() {
		Order order = loadOrder();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.FAILED);
		assertThat(cartProductIds())
			.containsExactlyInAnyOrderElementsOf(expectedRestoredProductIds());
		assertThat(countProductRows(PRODUCT_A)).isEqualTo(1);
	}

	private void assertDuplicateOrSuccess(Throwable failure) {
		if (failure == null) {
			return;
		}
		assertThat(failure)
			.isInstanceOfAny(CartException.class, OrderException.class);
		assertThat(((BusinessException) failure).getErrorCode())
			.isIn(ErrorCode.CART_ITEM_DUPLICATED, ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);
	}

	private Order loadOrder() {
		return databaseStateProbe.loadOrder(ORDER_A);
	}

	private List<UUID> cartProductIds() {
		return databaseStateProbe.cartProductIds(BUYER_ID);
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

	private long countProductRows(UUID productId) {
		return databaseStateProbe.countCartProductRows(productId);
	}

	private ProductCartSnapshot productSnapshot() {
		return new ProductCartSnapshot(
			PRODUCT_A,
			"상품 A",
			"PDF",
			10_000,
			"thumbnail",
			SELLER_A,
			"판매자 A",
			"ON_SALE"
		);
	}

}
