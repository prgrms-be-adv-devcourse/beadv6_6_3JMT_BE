package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import com.prompthub.order.infra.persistence.order.AdminOrderQueryRepositoryImpl;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
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
@Import({QuerydslConfig.class, TestJpaConfig.class, AdminOrderQueryRepositoryImpl.class})
class AdminOrderQueryRepositoryImplTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private AdminOrderQueryRepositoryImpl adminOrderQueryRepository;

	@Test
	@DisplayName("관리자 주문 목록은 주문 단위로 조회하고 다건 상품명을 축약한다")
	void searchAdminOrders_groupsByOrderAndFormatsTitle() {
		Order order = createOrder("ORD-20260624-0001", OrderStatus.PAID, LocalDateTime.of(2026, 6, 24, 10, 0));
		order.addOrderProduct(OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT, PRODUCT_AMOUNT_1));
		order.addOrderProduct(OrderProduct.create(PRODUCT_ID_2, SELLER_ID_2, PRODUCT_TITLE_2, PRODUCT_TYPE_PROMPT, PRODUCT_AMOUNT_2));
		order.markPaid(LocalDateTime.of(2026, 6, 24, 10, 5));
		entityManager.persist(order);
		entityManager.flush();
		entityManager.clear();

		Page<AdminOrderListProjection> result = adminOrderQueryRepository.searchAdminOrders(
			new AdminOrderSearchCondition("ALL", 1, 20).resolve(),
			PageRequest.of(0, 20)
		);

		assertThat(result.getTotalElements()).isEqualTo(1);
		AdminOrderListProjection projection = result.getContent().getFirst();
		assertThat(projection.orderId()).isEqualTo(order.getId());
		assertThat(projection.sellerId()).isEqualTo(SELLER_ID_1);
		assertThat(projection.productTitle()).isEqualTo(PRODUCT_TITLE_1 + " 외 1건");
		assertThat(projection.totalOrderCount()).isEqualTo(2);
		assertThat(projection.totalOrderAmount()).isEqualTo(PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
		assertThat(projection.orderStatus()).isEqualTo(OrderStatus.PAID);
	}

	@Test
	@DisplayName("관리자 주문 목록은 주문 상태로 필터링한다")
	void searchAdminOrders_filtersByStatus() {
		Order paidOrder = createSingleProductPaidOrder("ORD-20260624-0002", LocalDateTime.of(2026, 6, 24, 10, 0));
		Order pendingOrder = createOrder("ORD-20260624-0003", OrderStatus.PENDING, LocalDateTime.of(2026, 6, 24, 11, 0));
		pendingOrder.addOrderProduct(OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT, PRODUCT_AMOUNT_1));
		entityManager.persist(paidOrder);
		entityManager.persist(pendingOrder);
		entityManager.flush();
		entityManager.clear();

		Page<AdminOrderListProjection> result = adminOrderQueryRepository.searchAdminOrders(
			new AdminOrderSearchCondition("PAID", 1, 20).resolve(),
			PageRequest.of(0, 20)
		);

		assertThat(result.getContent()).extracting(AdminOrderListProjection::orderId)
			.containsExactly(paidOrder.getId());
	}

	private Order createSingleProductPaidOrder(String orderNumber, LocalDateTime createdAt) {
		Order order = createOrder(orderNumber, OrderStatus.PAID, createdAt);
		order.addOrderProduct(OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT, PRODUCT_AMOUNT_1));
		order.markPaid(createdAt.plusMinutes(1));
		entityManager.persist(order);
		return order;
	}

	private Order createOrder(String orderNumber, OrderStatus status, LocalDateTime createdAt) {
		Order order = Order.create(BUYER_ID, orderNumber, PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2, 2);
		ReflectionTestUtils.setField(order, "createdAt", createdAt);
		ReflectionTestUtils.setField(order, "updatedAt", createdAt);
		if (status == OrderStatus.FAILED) {
			order.markFailed();
		}
		return order;
	}
}
