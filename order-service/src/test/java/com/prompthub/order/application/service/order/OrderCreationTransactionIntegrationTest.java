package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.infra.persistence.cart.CartPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_B1;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_C1;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_B;
import static com.prompthub.order.fixture.OrderV2Fixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderV2Fixture.command;
import static com.prompthub.order.fixture.OrderV2Fixture.requestedProductIds;
import static com.prompthub.order.fixture.OrderV2Fixture.shuffledSnapshots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class OrderCreationTransactionIntegrationTest {

	private static final UUID UNRELATED_PRODUCT =
		UUID.fromString("00000000-0000-0000-0000-000000000205");

	@Autowired
	private OrderCommandHandler orderCommandHandler;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private OutboxEventPersistence outboxEventPersistence;

	@Autowired
	private CartPersistence cartPersistence;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private OrderNumberGenerator orderNumberGenerator;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	@MockitoBean
	private OrderProductIdempotencyStore orderProductIdempotencyStore;

	@MockitoSpyBean
	private OrderRepository orderRepository;

	@MockitoSpyBean
	private CartRepository cartRepository;

	@BeforeEach
	void setUp() {
		given(orderNumberGenerator.generate()).willReturn("ORD-A", "ORD-B", "ORD-C");
		given(orderProductIdempotencyStore.acquire(
			any(UUID.class), anyCollection(), any(UUID.class), any(Duration.class)
		)).willReturn(true);
		given(productClient.getOrderSnapshots(requestedProductIds())).willAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return shuffledSnapshots();
		});
		willAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
			return invocation.callRealMethod();
		}).given(orderRepository).save(any());
	}

	@AfterEach
	void tearDown() {
		outboxEventPersistence.deleteAll();
		orderPersistence.deleteAll();
		cartPersistence.deleteAll();
		reset(productClient, orderNumberGenerator, orderExpirationStore, orderProductIdempotencyStore,
			orderRepository, cartRepository);
	}

	@Test
	@DisplayName("단일 주문·상품·장바구니 제거를 저장하고 주문 생성 Outbox는 저장하지 않는다")
	void createsOneOrderFourProductsWithoutOrderCreatedOutbox() {
		saveCart();

		CreateOrderResult result = orderCommandHandler.createOrder(BUYER_ID, command());

		assertThat(result.totalAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(result.order()).isNotNull();
		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(countOrderProducts()).isEqualTo(4);
		assertThat(outboxEventPersistence.count()).isZero();
		assertThat(productIds(loadCart())).containsExactly(UNRELATED_PRODUCT);
	}

	@Test
	@DisplayName("무료 주문은 완료·장바구니 제거·ORDER_PAID Outbox를 한 트랜잭션으로 반영한다")
	void freeOrderCompletesAndStoresPaidOutboxAtomically() {
		saveCart();
		given(productClient.getOrderSnapshots(requestedProductIds())).willReturn(freeSnapshots());

		CreateOrderResult result = orderCommandHandler.createOrder(BUYER_ID, command());
		Order saved = orderRepository.findByIdWithOrderProducts(result.order().orderId()).orElseThrow();

		assertThat(result.totalAmount()).isZero();
		assertThat(saved.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(saved.getCompletedAt()).isNotNull();
		assertThat(saved.getOrderProducts()).extracting(product -> product.getOrderStatus())
			.containsOnly(OrderProductStatus.PAID);
		assertThat(outboxEventPersistence.findAll()).singleElement().satisfies(event -> {
			assertThat(event.getEventType()).isEqualTo("ORDER_PAID");
			assertThat(event.getPayload()).contains("\"totalOrderAmount\":0");
		});
		assertThat(productIds(loadCart())).containsExactly(UNRELATED_PRODUCT);
		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("장바구니 저장이 실패하면 주문·상품·장바구니 제거를 모두 롤백한다")
	void cartSaveFailureRollsBackOrderProductsAndCartRemoval() {
		saveCart();
		willThrow(new RuntimeException("cart save failure"))
			.given(cartRepository).save(any(Cart.class));

		assertThatThrownBy(() -> orderCommandHandler.createOrder(BUYER_ID, command()))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("cart save failure");

		assertThat(orderPersistence.count()).isZero();
		assertThat(countOrderProducts()).isZero();
		assertThat(outboxEventPersistence.count()).isZero();
		assertThat(productIds(loadCart()))
			.containsExactlyInAnyOrder(PRODUCT_A1, PRODUCT_A2, PRODUCT_B1, PRODUCT_C1, UNRELATED_PRODUCT);
		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("첫 단건 주문 번호가 충돌하면 신규 주문과 상품이 모두 롤백된다")
	void orderNumberConflictRollsBackSingleOrder() {
		Order existing = Order.create(BUYER_ID, "ORD-A", 1_000);
		orderPersistence.saveAndFlush(existing);
		given(orderNumberGenerator.generate()).willReturn("ORD-A");

		assertThatThrownBy(() -> orderCommandHandler.createOrder(BUYER_ID, command()))
			.isInstanceOfAny(DataIntegrityViolationException.class, RuntimeException.class);

		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(countOrderProducts()).isZero();
		assertThat(outboxEventPersistence.count()).isZero();
		then(orderExpirationStore).shouldHaveNoInteractions();
	}

	private Cart saveCart() {
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_A1);
		cart.addProduct(PRODUCT_A2);
		cart.addProduct(PRODUCT_B1);
		cart.addProduct(PRODUCT_C1);
		cart.addProduct(UNRELATED_PRODUCT);
		return cartPersistence.saveAndFlush(cart);
	}

	private List<ProductOrderSnapshot> freeSnapshots() {
		return shuffledSnapshots().stream()
			.map(snapshot -> new ProductOrderSnapshot(
				snapshot.productId(), snapshot.sellerId(), snapshot.title(),
				snapshot.productType(), snapshot.model(), 0
			))
			.toList();
	}

	private Cart loadCart() {
		return cartPersistence.findByBuyerIdWithCartProducts(BUYER_ID).orElseThrow();
	}

	private List<UUID> productIds(Cart cart) {
		return cart.getCartProducts().stream()
			.map(product -> product.getProductId())
			.toList();
	}

	private long countOrderProducts() {
		Long count = jdbcTemplate.queryForObject("select count(*) from order_product", Long.class);
		return count == null ? 0L : count;
	}
}
