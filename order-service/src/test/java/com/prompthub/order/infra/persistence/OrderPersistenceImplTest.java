package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.OrderListProductProjection;
import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.CREATED_AT;
import static com.prompthub.order.fixture.OrderFixture.PAID_AT;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_MODEL;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TYPE_PROMPT;
import static com.prompthub.order.fixture.OrderFixture.REFUNDED_AT;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_2;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
class OrderPersistenceImplTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private OrderPersistence orderPersistence;

	@Test
	@DisplayName("주문 목록은 주문 단위로 페이지하고 전체 주문 수를 반환한다")
	void searchOrders_pagesAndCountsByOrder() {
		Order oldOrder = createPaidOrder("ORD-20260619-0001", LocalDateTime.of(2026, 6, 19, 10, 0), false);
		Order newOrder = createPaidOrder("ORD-20260620-0001", LocalDateTime.of(2026, 6, 20, 10, 0), true);

		entityManager.persist(oldOrder);
		entityManager.persist(newOrder);
		entityManager.flush();
		entityManager.clear();

		Page<OrderListProjection> result = orderPersistence.searchOrders(
			BUYER_ID,
			OrderStatus.PAID,
			null,
			null,
			PageRequest.of(0, 1)
		);

		assertThat(result.getContent()).hasSize(1);
		assertThat(result.getTotalElements()).isEqualTo(2);
		assertThat(result.getContent().getFirst().orderId()).isEqualTo(newOrder.getId());
		assertThat(result.getContent().getFirst().orderNumber()).isEqualTo(newOrder.getOrderNumber());
		assertThat(result.getContent().getFirst().totalAmount()).isEqualTo(PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
	}

	@Test
	@DisplayName("선택한 주문들의 모든 주문상품을 주문상품 ID 오름차순으로 조회한다")
	void findOrderProductsByOrderIds_returnsAllProductsInStableOrder() {
		Order order = createPaidOrder("ORD-20260620-0002", LocalDateTime.of(2026, 6, 20, 10, 0), true);
		entityManager.persist(order);
		entityManager.flush();
		entityManager.clear();

		List<OrderListProductProjection> products = orderPersistence.findOrderProductsByOrderIds(List.of(order.getId()));
		List<UUID> expectedProductIds = order.getOrderProducts().stream()
			.map(OrderProduct::getId)
			.sorted(Comparator.comparing(UUID::toString))
			.toList();

		assertThat(products).hasSize(2);
		assertThat(products).extracting(OrderListProductProjection::orderId).containsOnly(order.getId());
		assertThat(products).extracting(OrderListProductProjection::orderProductId)
			.containsExactlyElementsOf(expectedProductIds);
		assertThat(products).extracting(OrderListProductProjection::productAmount)
			.containsExactlyInAnyOrder(PRODUCT_AMOUNT_1, PRODUCT_AMOUNT_2);
	}

	@Test
	@DisplayName("환불 요청 주문의 남은 결제 상품만 열람 가능한 구매로 조회한다")
	void findAccessiblePaidProducts_refundRequestedOrder_returnsRemainingPaidProduct() {
		Order order = Order.create(BUYER_ID, "ORD-20260721-0001", PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
		OrderProduct refundRequested = OrderProduct.create(
			PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_1
		);
		OrderProduct remainingPaid = OrderProduct.create(
			PRODUCT_ID_2, SELLER_ID_2, PRODUCT_TITLE_2, PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_2
		);
		order.addOrderProduct(refundRequested);
		order.addOrderProduct(remainingPaid);
		order.markPaid(LocalDateTime.of(2026, 7, 21, 10, 0));
		order.requestRefund(List.of(refundRequested.getId()));
		entityManager.persist(order);
		entityManager.flush();
		entityManager.clear();

		assertThat(orderPersistence.existsAccessiblePaidOrderProductByBuyerIdAndProductId(BUYER_ID, PRODUCT_ID_1))
			.isFalse();
		assertThat(orderPersistence.existsAccessiblePaidOrderProductByBuyerIdAndProductId(BUYER_ID, PRODUCT_ID_2))
			.isTrue();
		assertThat(orderPersistence.findAccessiblePaidProductIdsByBuyerId(BUYER_ID))
			.containsExactly(PRODUCT_ID_2);
	}

	@Test
	@DisplayName("결제 대기·완료·환불 요청 상품은 구매 차단 상태로 조회한다")
	void existsBlockingOrderProduct_returnsTrueForPendingPaidAndRefundRequested() {
		UUID pendingProduct = UUID.fromString("00000000-0000-0000-0000-000000000711");
		UUID paidProduct = UUID.fromString("00000000-0000-0000-0000-000000000712");
		UUID refundRequestedProduct = UUID.fromString("00000000-0000-0000-0000-000000000713");
		UUID failedProduct = UUID.fromString("00000000-0000-0000-0000-000000000714");
		UUID refundedProduct = UUID.fromString("00000000-0000-0000-0000-000000000715");

		Order pending = orderWithProduct("ORD-BLOCK-PENDING", pendingProduct);
		Order paid = orderWithProduct("ORD-BLOCK-PAID", paidProduct);
		paid.markPaid(PAID_AT);
		Order refundRequested = orderWithProduct("ORD-BLOCK-REQUESTED", refundRequestedProduct);
		refundRequested.markPaid(PAID_AT);
		refundRequested.requestRefund(List.of(refundRequested.getOrderProducts().getFirst().getId()));
		Order failed = orderWithProduct("ORD-BLOCK-FAILED", failedProduct);
		failed.markFailed(CREATED_AT.plusMinutes(1));
		Order refunded = orderWithProduct("ORD-BLOCK-REFUNDED", refundedProduct);
		refunded.markPaid(PAID_AT);
		refunded.refundOrderProduct(
			refunded.getOrderProducts().getFirst().getId(),
			PRODUCT_AMOUNT_1,
			REFUNDED_AT
		);

		List.of(pending, paid, refundRequested, failed, refunded).forEach(entityManager::persist);
		entityManager.flush();
		entityManager.clear();

		assertThat(orderPersistence.existsBlockingOrderProductByBuyerIdAndProductId(BUYER_ID, pendingProduct))
			.isTrue();
		assertThat(orderPersistence.existsBlockingOrderProductByBuyerIdAndProductId(BUYER_ID, paidProduct))
			.isTrue();
		assertThat(orderPersistence.existsBlockingOrderProductByBuyerIdAndProductId(BUYER_ID, refundRequestedProduct))
			.isTrue();
		assertThat(orderPersistence.existsBlockingOrderProductByBuyerIdAndProductId(BUYER_ID, failedProduct))
			.isFalse();
		assertThat(orderPersistence.existsBlockingOrderProductByBuyerIdAndProductId(BUYER_ID, refundedProduct))
			.isFalse();
	}

	@Test
	@DisplayName("DB 만료 조회는 cutoff 이전의 CREATED 주문만 오래된 순서로 반환한다")
	void findExpiredCreatedOrderIds_returnsOnlyExpiredCreatedOrders() {
		LocalDateTime cutoff = LocalDateTime.now().plusMinutes(1);
		Order expired = orderWithProduct("ORD-EXPIRED", UUID.fromString("00000000-0000-0000-0000-000000000721"));
		Order completed = orderWithProduct("ORD-EXPIRED-COMPLETED", UUID.fromString("00000000-0000-0000-0000-000000000723"));
		completed.markPaid(PAID_AT);

		List.of(expired, completed).forEach(entityManager::persist);
		entityManager.flush();
		entityManager.clear();

		assertThat(orderPersistence.findExpiredCreatedOrderIds(cutoff, PageRequest.of(0, 10)))
			.containsExactly(expired.getId());
	}

	private Order orderWithProduct(String orderNumber, UUID productId) {
		Order order = Order.create(BUYER_ID, orderNumber, PRODUCT_AMOUNT_1);
		order.addOrderProduct(OrderProduct.create(
			productId,
			SELLER_ID_1,
			PRODUCT_TITLE_1,
			PRODUCT_TYPE_PROMPT,
			PRODUCT_MODEL,
			PRODUCT_AMOUNT_1
		));
		ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(order, "updatedAt", CREATED_AT);
		return order;
	}

	private Order createPaidOrder(String orderNumber, LocalDateTime createdAt, boolean includeSecondProduct) {
		Order order = Order.create(
			BUYER_ID,
			orderNumber,
			includeSecondProduct ? PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2 : PRODUCT_AMOUNT_1
		);
		order.addOrderProduct(OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT,
			"GPT-4",
			PRODUCT_AMOUNT_1
		));
		if (includeSecondProduct) {
			order.addOrderProduct(OrderProduct.create(
				PRODUCT_ID_2,
				SELLER_ID_2,
				PRODUCT_TITLE_2,
				PRODUCT_TYPE_PROMPT,
				"Claude-3",
				PRODUCT_AMOUNT_2
			));
		}
		order.markPaid(createdAt.plusMinutes(1));
		ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(order, "createdAt", createdAt);
		ReflectionTestUtils.setField(order, "updatedAt", createdAt);
		return order;
	}
}
