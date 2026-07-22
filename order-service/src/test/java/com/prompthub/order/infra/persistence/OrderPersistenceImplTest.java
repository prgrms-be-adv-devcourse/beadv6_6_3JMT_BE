package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.dto.OrderListProjection;
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
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TYPE_PROMPT;
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
	@DisplayName("주문 목록은 주문 생성일 최신순, 주문상품 ID 오름차순으로 조회한다")
	void searchOrderProducts_ordersByCreatedAtDescAndOrderProductIdAsc() {
		Order oldOrder = createPaidOrder("ORD-20260619-0001", LocalDateTime.of(2026, 6, 19, 10, 0));
		Order newOrder = createPaidOrder("ORD-20260620-0001", LocalDateTime.of(2026, 6, 20, 10, 0));

		entityManager.persist(oldOrder);
		entityManager.persist(newOrder);
		entityManager.flush();
		entityManager.clear();

		Page<OrderListProjection> result = orderPersistence.searchOrderProducts(
			BUYER_ID,
			OrderStatus.PAID,
			null,
			null,
			PageRequest.of(0, 20)
		);

		assertThat(result.getContent())
			.extracting(OrderListProjection::orderId)
			.containsExactly(newOrder.getId(), oldOrder.getId());
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

	private Order createPaidOrder(String orderNumber, LocalDateTime createdAt) {
		Order order = Order.create(
			BUYER_ID,
			orderNumber,
			PRODUCT_AMOUNT_1
		);
		order.addOrderProduct(OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT,
			"GPT-4",
			PRODUCT_AMOUNT_1
		));
		order.markPaid(createdAt.plusMinutes(1));
		ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(order, "createdAt", createdAt);
		ReflectionTestUtils.setField(order, "updatedAt", createdAt);
		return order;
	}
}
