package com.prompthub.order.application.service.order;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.application.service.event.ProcessedEventService;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.ProcessedEventRepository;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import com.prompthub.order.infra.persistence.cart.CartPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.support.DatabaseStateProbe;
import com.prompthub.order.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_B;
import static com.prompthub.order.fixture.PaymentEventFixture.OTHER_BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_B;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_C;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_D;
import static com.prompthub.order.fixture.PaymentEventFixture.SELLER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.SELLER_B;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static com.prompthub.order.fixture.PaymentEventFixture.failedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.productIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

class OrderFailureCompensationJpaTest extends PostgreSqlIntegrationTestSupport {

	private static final String EVENT_TYPE = "PAYMENT_FAILED";
	private static final String ORDER_CONSUMER_GROUP = "order-service";
	private static final UUID UNRELATED_PRODUCT = uuid(799);

	@Autowired
	private OrderFailureCompensationService compensationService;

	@Autowired
	private ProcessedEventService processedEventService;

	@Autowired
	private ProcessedEventRepository processedEventRepository;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private CartPersistence cartPersistence;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private SellerClient sellerClient;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	private DatabaseStateProbe databaseStateProbe;

	@BeforeEach
	void setUp() {
		reset(productClient, sellerClient, orderExpirationStore);
		databaseStateProbe = new DatabaseStateProbe(
			orderPersistence,
			cartPersistence,
			jdbcTemplate,
			transactionManager
		);
	}

	@Test
	@DisplayName("COMP-PG-01 장바구니 상품 네 개 주문 실패 시 네 개가 모두 복구된다")
	void paymentFailureRestoresAllFourProducts() {
		orderPersistence.saveAndFlush(createdOrder());
		cartPersistence.saveAndFlush(Cart.create(BUYER_ID));

		compensateFailure(uuid(901), failedPayload());

		assertFailedOrderWithProducts(ORDER_A, productIds());
		assertThat(countRows("cart_product")).isEqualTo(4);
		assertThat(processedEventRepository.count()).isEqualTo(1);
		assertThat(countRows("\"order\"")).isEqualTo(1);
		assertThat(countDistinctOrderSellers(ORDER_A)).isGreaterThanOrEqualTo(2);
	}

	@Test
	@DisplayName("COMP-PG-02 바로 구매 단건도 새 Cart에 복구된다")
	void directPurchaseRestoresSingleProductIntoNewCart() {
		Order order = orderWithProducts(uuid(510), BUYER_ID, "ORD-DIRECT", List.of(PRODUCT_A));
		orderPersistence.saveAndFlush(order);

		compensateFailure(uuid(902), failurePayload(order));

		assertFailedOrderWithProducts(order.getId(), List.of(PRODUCT_A));
		assertThat(countCarts(BUYER_ID)).isEqualTo(1);
		assertThat(processedEventRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("COMP-PG-03 이미 Cart에 있는 두 상품은 중복 추가되지 않는다")
	void partialExistingCartKeepsExactlyOneRowPerProduct() {
		orderPersistence.saveAndFlush(createdOrder());
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_A);
		cart.addProduct(PRODUCT_C);
		cartPersistence.saveAndFlush(cart);

		compensateFailure(uuid(903), failedPayload());

		assertThat(new LinkedHashSet<>(databaseStateProbe.cartProductIds(BUYER_ID)))
			.containsExactlyInAnyOrderElementsOf(productIds());
		assertThat(countRows("cart_product")).isEqualTo(4);
		productIds().forEach(productId ->
			assertThat(databaseStateProbe.countCartProductRows(productId)).isEqualTo(1)
		);
	}

	@Test
	@DisplayName("COMP-PG-04 Cart가 없으면 구매자 Cart 하나만 생성하고 모든 상품을 연결한다")
	void missingCartCreatesOneBuyerCartForAllProducts() {
		orderPersistence.saveAndFlush(createdOrder());

		compensateFailure(uuid(904), failedPayload());

		assertThat(countCarts(BUYER_ID)).isEqualTo(1);
		assertThat(countDistinctProductCartRoots()).isEqualTo(1);
		assertThat(new LinkedHashSet<>(databaseStateProbe.cartProductIds(BUYER_ID)))
			.containsExactlyInAnyOrderElementsOf(productIds());
	}

	@Test
	@DisplayName("COMP-PG-05 CREATED 주문은 PENDING 상품만 FAILED로 바꾸고 기존 상품 행을 보존한다")
	void mixedProductStatesOnlyFailPendingProducts() {
		Order order = createdOrder();
		setProductStatus(order, PRODUCT_B, OrderProductStatus.PAID);
		setProductStatus(order, PRODUCT_C, OrderProductStatus.FAILED);
		LocalDateTime originalUpdatedAt = FAILED_AT.minusDays(1);
		order.getOrderProducts().forEach(product ->
			ReflectionTestUtils.setField(product, "updatedAt", originalUpdatedAt)
		);
		orderPersistence.saveAndFlush(order);
		Map<UUID, OrderProductRowSnapshot> before = orderProductRows(ORDER_A);

		compensateFailure(uuid(905), failedPayload());

		Order restored = databaseStateProbe.loadOrder(ORDER_A);
		Map<UUID, OrderProductRowSnapshot> after = orderProductRows(ORDER_A);
		assertThat(productStatusById(restored)).containsExactlyInAnyOrderEntriesOf(Map.of(
			PRODUCT_A, OrderProductStatus.FAILED,
			PRODUCT_B, OrderProductStatus.PAID,
			PRODUCT_C, OrderProductStatus.FAILED,
			PRODUCT_D, OrderProductStatus.FAILED
		));
		assertThat(restored.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		productIds().forEach(productId ->
			assertThat(after.get(productId).id()).isEqualTo(before.get(productId).id())
		);
		assertThat(after.get(PRODUCT_A).updatedAt()).isNotEqualTo(before.get(PRODUCT_A).updatedAt());
		assertThat(after.get(PRODUCT_D).updatedAt()).isNotEqualTo(before.get(PRODUCT_D).updatedAt());
		assertThat(after.get(PRODUCT_B)).isEqualTo(before.get(PRODUCT_B));
		assertThat(after.get(PRODUCT_C)).isEqualTo(before.get(PRODUCT_C));
	}

	@Test
	@DisplayName("COMP-PG-06 이미 FAILED인 주문의 timeout과 새 실패 이벤트는 도메인 행과 timestamp를 변경하지 않는다")
	void failedOrderRecompensationIsNoOpExceptNewProcessedEvent() {
		orderPersistence.saveAndFlush(createdOrder());
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(UNRELATED_PRODUCT);
		cartPersistence.saveAndFlush(cart);
		compensateFailure(uuid(906), failedPayload());
		DurableCompensationSnapshot afterFirstFailure = durableSnapshot(ORDER_A, BUYER_ID);
		LocalDateTime timedOutAt = databaseStateProbe.loadOrder(ORDER_A).getCreatedAt().plusMinutes(20);

		boolean timeoutHandled = compensationService.compensateTimeout(ORDER_A, timedOutAt);

		assertThat(timeoutHandled).isTrue();
		assertThat(durableSnapshot(ORDER_A, BUYER_ID)).isEqualTo(afterFirstFailure);

		compensateFailure(uuid(907), failedPayload());

		assertThat(durableSnapshot(ORDER_A, BUYER_ID)).isEqualTo(afterFirstFailure);
		assertThat(processedEventRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("COMP-PG-07 COMPLETED 주문의 늦은 실패는 주문과 Cart를 변경하지 않는다")
	void completedOrderLateFailureOnlyStoresProcessedEvent() {
		Order order = createdOrder();
		order.markCompleted(APPROVED_AT);
		orderPersistence.saveAndFlush(order);
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(UNRELATED_PRODUCT);
		cartPersistence.saveAndFlush(cart);

		compensateFailure(uuid(908), failedPayload());

		Order restored = databaseStateProbe.loadOrder(ORDER_A);
		assertThat(restored.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(restored.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		assertThat(databaseStateProbe.cartProductIds(BUYER_ID)).containsExactly(UNRELATED_PRODUCT);
		assertThat(processedEventRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("COMP-PG-08 payment failure와 timeout의 핵심 DB 결과가 같다")
	void paymentFailureAndTimeoutProduceEquivalentDatabaseResults() {
		Order failedEventOrder = createdOrder();
		Order timeoutOrder = orderWithProducts(ORDER_B, OTHER_BUYER_ID, "ORD-B", productIds());
		orderPersistence.saveAndFlush(failedEventOrder);
		orderPersistence.saveAndFlush(timeoutOrder);

		compensateFailure(uuid(909), failedPayload());
		LocalDateTime timedOutAt = databaseStateProbe.loadOrder(ORDER_B).getCreatedAt().plusMinutes(20);
		boolean timeoutHandled = compensationService.compensateTimeout(ORDER_B, timedOutAt);

		assertThat(timeoutHandled).isTrue();
		assertThat(snapshot(ORDER_A, BUYER_ID))
			.isEqualTo(snapshot(ORDER_B, OTHER_BUYER_ID));
		assertThat(processedEventRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("COMP-PG-09 같은 eventId도 consumerGroup이 다르면 처리 이력을 구분한다")
	void sameEventIdWithDifferentConsumerGroupsStoresTwoProcessedEvents() {
		UUID eventId = uuid(910);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status -> {
			processedEventService.markProcessed(
				eventId,
				ORDER_CONSUMER_GROUP,
				EVENT_TYPE,
				FAILED_AT
			);
			processedEventService.markProcessed(
				eventId,
				"settlement-projection",
				EVENT_TYPE,
				FAILED_AT
			);
		});

		assertThat(processedEventRepository.count()).isEqualTo(2);
		assertThat(processedEventService.isProcessed(eventId, ORDER_CONSUMER_GROUP)).isTrue();
		assertThat(processedEventService.isProcessed(eventId, "settlement-projection")).isTrue();
	}

	private void compensateFailure(UUID eventId, PaymentFailedPayload payload) {
		compensationService.compensatePaymentFailure(eventId, EVENT_TYPE, FAILED_AT, payload);
	}

	private void assertFailedOrderWithProducts(UUID orderId, List<UUID> expectedProductIds) {
		Order order = databaseStateProbe.loadOrder(orderId);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.FAILED);
		assertThat(new LinkedHashSet<>(databaseStateProbe.cartProductIds(order.getBuyerId())))
			.containsExactlyInAnyOrderElementsOf(expectedProductIds);
	}

	private CompensationSnapshot snapshot(UUID orderId, UUID buyerId) {
		Order order = databaseStateProbe.loadOrder(orderId);
		return new CompensationSnapshot(
			order.getOrderStatus(),
			productStatusById(order),
			new LinkedHashSet<>(databaseStateProbe.cartProductIds(buyerId)),
			countCartProductsForBuyer(buyerId)
		);
	}

	private DurableCompensationSnapshot durableSnapshot(UUID orderId, UUID buyerId) {
		OrderRowSnapshot order = jdbcTemplate.queryForObject(
			"""
			select id, order_status, created_at, updated_at, completed_at, refunded_at
			from "order"
			where id = ?
			""",
			(rs, rowNum) -> new OrderRowSnapshot(
				rs.getObject("id", UUID.class),
				OrderStatus.valueOf(rs.getString("order_status")),
				rs.getObject("created_at", LocalDateTime.class),
				rs.getObject("updated_at", LocalDateTime.class),
				rs.getObject("completed_at", LocalDateTime.class),
				rs.getObject("refunded_at", LocalDateTime.class)
			),
			orderId
		);
		CartRowSnapshot cart = jdbcTemplate.queryForObject(
			"""
			select id, buyer_id, total_amount, created_at, updated_at
			from cart
			where buyer_id = ?
			""",
			(rs, rowNum) -> new CartRowSnapshot(
				rs.getObject("id", UUID.class),
				rs.getObject("buyer_id", UUID.class),
				rs.getInt("total_amount"),
				rs.getObject("created_at", LocalDateTime.class),
				rs.getObject("updated_at", LocalDateTime.class)
			),
			buyerId
		);
		return new DurableCompensationSnapshot(
			order,
			List.copyOf(orderProductRows(orderId).values()),
			cart,
			cartProductRows(buyerId)
		);
	}

	private Map<UUID, OrderProductRowSnapshot> orderProductRows(UUID orderId) {
		List<OrderProductRowSnapshot> rows = jdbcTemplate.query(
			"""
			select id, product_id, order_product_status, created_at, updated_at, refunded_at, downloaded
			from order_product
			where order_id = ?
			order by product_id
			""",
			(rs, rowNum) -> new OrderProductRowSnapshot(
				rs.getObject("id", UUID.class),
				rs.getObject("product_id", UUID.class),
				OrderProductStatus.valueOf(rs.getString("order_product_status")),
				rs.getObject("created_at", LocalDateTime.class),
				rs.getObject("updated_at", LocalDateTime.class),
				rs.getObject("refunded_at", LocalDateTime.class),
				rs.getBoolean("downloaded")
			),
			orderId
		);
		Map<UUID, OrderProductRowSnapshot> snapshots = new LinkedHashMap<>();
		rows.forEach(row -> snapshots.put(row.productId(), row));
		return snapshots;
	}

	private List<CartProductRowSnapshot> cartProductRows(UUID buyerId) {
		return jdbcTemplate.query(
			"""
			select cp.id, cp.product_id, cp.created_at, cp.updated_at, cp.added_at
			from cart_product cp
			join cart c on c.id = cp.cart_id
			where c.buyer_id = ?
			order by cp.product_id
			""",
			(rs, rowNum) -> new CartProductRowSnapshot(
				rs.getObject("id", UUID.class),
				rs.getObject("product_id", UUID.class),
				rs.getObject("created_at", LocalDateTime.class),
				rs.getObject("updated_at", LocalDateTime.class),
				rs.getObject("added_at", LocalDateTime.class)
			),
			buyerId
		);
	}

	private Map<UUID, OrderProductStatus> productStatusById(Order order) {
		Map<UUID, OrderProductStatus> statuses = new LinkedHashMap<>();
		order.getOrderProducts().stream()
			.sorted((left, right) -> left.getProductId().compareTo(right.getProductId()))
			.forEach(product -> statuses.put(product.getProductId(), product.getOrderStatus()));
		return statuses;
	}

	private Order orderWithProducts(UUID orderId, UUID buyerId, String orderNumber, List<UUID> products) {
		Order order = Order.create(buyerId, orderNumber, products.size() * 10_000);
		ReflectionTestUtils.setField(order, "id", orderId);
		for (int index = 0; index < products.size(); index++) {
			UUID sellerId = index % 2 == 0 ? SELLER_A : SELLER_B;
			order.addOrderProduct(OrderProduct.create(
				products.get(index),
				sellerId,
				"상품-" + index,
				10_000
			));
		}
		return order;
	}

	private PaymentFailedPayload failurePayload(Order order) {
		return new PaymentFailedPayload(PAYMENT_ID, order.getId(), order.getBuyerId());
	}

	private void setProductStatus(Order order, UUID productId, OrderProductStatus status) {
		OrderProduct product = order.getOrderProducts().stream()
			.filter(candidate -> candidate.getProductId().equals(productId))
			.findFirst()
			.orElseThrow();
		ReflectionTestUtils.setField(product, "orderStatus", status);
	}

	private long countRows(String tableName) {
		Long count = jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
		return count == null ? 0 : count;
	}

	private long countCarts(UUID buyerId) {
		Long count = jdbcTemplate.queryForObject(
			"select count(*) from cart where buyer_id = ?",
			Long.class,
			buyerId
		);
		return count == null ? 0 : count;
	}

	private long countCartProductsForBuyer(UUID buyerId) {
		Long count = jdbcTemplate.queryForObject(
			"""
			select count(*)
			from cart_product cp
			join cart c on c.id = cp.cart_id
			where c.buyer_id = ?
			""",
			Long.class,
			buyerId
		);
		return count == null ? 0 : count;
	}

	private long countDistinctProductCartRoots() {
		Long count = jdbcTemplate.queryForObject(
			"select count(distinct cart_id) from cart_product",
			Long.class
		);
		return count == null ? 0 : count;
	}

	private long countDistinctOrderSellers(UUID orderId) {
		Long count = jdbcTemplate.queryForObject(
			"select count(distinct seller_id) from order_product where order_id = ?",
			Long.class,
			orderId
		);
		return count == null ? 0 : count;
	}

	private static UUID uuid(long suffix) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
	}

	private record CompensationSnapshot(
		OrderStatus orderStatus,
		Map<UUID, OrderProductStatus> productStatuses,
		Set<UUID> cartProductIds,
		long cartProductRows
	) {
	}

	private record DurableCompensationSnapshot(
		OrderRowSnapshot order,
		List<OrderProductRowSnapshot> orderProducts,
		CartRowSnapshot cart,
		List<CartProductRowSnapshot> cartProducts
	) {
	}

	private record OrderRowSnapshot(
		UUID id,
		OrderStatus status,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		LocalDateTime completedAt,
		LocalDateTime refundedAt
	) {
	}

	private record OrderProductRowSnapshot(
		UUID id,
		UUID productId,
		OrderProductStatus status,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		LocalDateTime refundedAt,
		boolean downloaded
	) {
	}

	private record CartRowSnapshot(
		UUID id,
		UUID buyerId,
		int totalAmount,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
	) {
	}

	private record CartProductRowSnapshot(
		UUID id,
		UUID productId,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		LocalDateTime addedAt
	) {
	}
}
