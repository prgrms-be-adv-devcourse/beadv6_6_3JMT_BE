package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.CartProduct;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.ProcessedEventRepository;
import com.prompthub.order.infra.persistence.cart.CartPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
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

class OrderFailureCompensationTransactionIntegrationTest extends PostgreSqlIntegrationTestSupport {

	private static final UUID UNRELATED_PRODUCT =
		UUID.fromString("00000000-0000-0000-0000-000000000799");

	@Autowired
	private OrderFailureCompensationService compensationService;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private CartPersistence cartPersistence;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private SellerClient sellerClient;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	@MockitoSpyBean
	private CartRepository cartRepository;

	@MockitoSpyBean
	private ProcessedEventRepository processedEventRepository;

	@BeforeEach
	void setUp() {
		cleanDatabase();
		reset(productClient, sellerClient, orderExpirationStore, cartRepository, processedEventRepository);
	}

	@AfterEach
	void tearDown() {
		reset(productClient, sellerClient, orderExpirationStore, cartRepository, processedEventRepository);
		cleanDatabase();
	}

	@Test
	void paymentFailureCommitsOrderProductsCartProcessedEventAndAfterCommitCleanup() {
		saveScenario();
		UUID eventId = UUID.randomUUID();

		compensationService.compensatePaymentFailure(
			eventId,
			"PAYMENT_FAILED",
			FAILED_AT,
			failedPayload()
		);

		assertFailedStateAndRestoredCart();
		assertThat(processedEventRepository.count()).isEqualTo(1);
		then(orderExpirationStore).should().removeExpiration(ORDER_A);
		then(orderExpirationStore).should().clearRetryCount(ORDER_A);
	}

	@Test
	void cartSaveFailureRollsBackOrderProductsCartAndProcessedEventWithoutCleanup() {
		saveScenario();
		willThrow(new RuntimeException("cart save failure"))
			.given(cartRepository).save(any(Cart.class));

		assertThatThrownBy(() -> compensationService.compensatePaymentFailure(
			UUID.randomUUID(),
			"PAYMENT_FAILED",
			FAILED_AT,
			failedPayload()
		))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("cart save failure");

		assertCreatedStateAndUnchangedCart();
		assertThat(processedEventRepository.count()).isZero();
		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	@Test
	void processedEventFailureRollsBackOrderProductsAndCartWithoutCleanup() {
		saveScenario();
		willThrow(new RuntimeException("processed event failure"))
			.given(processedEventRepository).save(any());

		assertThatThrownBy(() -> compensationService.compensatePaymentFailure(
			UUID.randomUUID(),
			"PAYMENT_FAILED",
			FAILED_AT,
			failedPayload()
		))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("processed event failure");

		assertCreatedStateAndUnchangedCart();
		assertThat(processedEventRepository.count()).isZero();
		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	@Test
	void duplicatePaymentFailureKeepsSingleCartRowsAndProcessedEvent() {
		saveScenario();
		UUID eventId = UUID.randomUUID();

		compensationService.compensatePaymentFailure(
			eventId,
			"PAYMENT_FAILED",
			FAILED_AT,
			failedPayload()
		);
		compensationService.compensatePaymentFailure(
			eventId,
			"PAYMENT_FAILED",
			FAILED_AT,
			failedPayload()
		);

		assertFailedStateAndRestoredCart();
		assertThat(processedEventRepository.count()).isEqualTo(1);
		then(orderExpirationStore).should(times(2)).removeExpiration(ORDER_A);
	}

	@Test
	void repeatedTimeoutKeepsSingleCartRows() {
		saveScenario();
		LocalDateTime expiredAt = loadOrder().getCreatedAt().plusMinutes(20);

		compensationService.compensateTimeout(ORDER_A, expiredAt);
		compensationService.compensateTimeout(ORDER_A, expiredAt.plusSeconds(1));

		assertFailedStateAndRestoredCart();
		assertThat(processedEventRepository.count()).isZero();
		then(orderExpirationStore).should(times(2)).removeExpiration(ORDER_A);
	}

	private void saveScenario() {
		orderPersistence.saveAndFlush(createdOrder());
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(UNRELATED_PRODUCT);
		cartPersistence.saveAndFlush(cart);
	}

	private void assertFailedStateAndRestoredCart() {
		Order order = loadOrder();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.FAILED);
		assertThat(cartProductIds())
			.containsExactlyInAnyOrderElementsOf(expectedRestoredProductIds());
		assertThat(count("cart_product")).isEqualTo(5);
	}

	private void assertCreatedStateAndUnchangedCart() {
		Order order = loadOrder();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PENDING);
		assertThat(cartProductIds()).containsExactly(UNRELATED_PRODUCT);
		assertThat(count("cart_product")).isEqualTo(1);
	}

	private Order loadOrder() {
		return orderPersistence.findByIdWithOrderProducts(ORDER_A).orElseThrow();
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

	private long count(String table) {
		Long count = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
		return count == null ? 0 : count;
	}

	private void cleanDatabase() {
		jdbcTemplate.update("delete from order_processed_event");
		jdbcTemplate.update("delete from order_outbox_event");
		jdbcTemplate.update("delete from order_product");
		jdbcTemplate.update("delete from \"order\"");
		jdbcTemplate.update("delete from cart_product");
		jdbcTemplate.update("delete from cart");
	}
}
