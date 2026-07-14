package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.application.dto.AdminDailyTransactionProjection;
import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
		order.addOrderProduct(OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_1));
		order.addOrderProduct(OrderProduct.create(PRODUCT_ID_2, SELLER_ID_2, PRODUCT_TITLE_2, PRODUCT_TYPE_PROMPT, "Claude-3", PRODUCT_AMOUNT_2));
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
		pendingOrder.addOrderProduct(OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_1));
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

	@Test
	@DisplayName("실제 거래액은 승인 금액에서 기간 내 취소/환불 금액을 차감한다")
	void sumMonthlyTransactionAmount_subtractsCanceledAndRefunded() {
		LocalDateTime start = LocalDateTime.of(2026, 6, 1, 0, 0);
		LocalDateTime endExclusive = LocalDateTime.of(2026, 7, 1, 0, 0);
		Order paidOrder = createSingleProductPaidOrder("ORD-20260624-0004", LocalDateTime.of(2026, 6, 10, 10, 0));
		persistPayment(paidOrder, UUID.fromString("00000000-0000-0000-0000-000000000411"), PRODUCT_AMOUNT_1, LocalDateTime.of(2026, 6, 10, 10, 1));

		Order canceledOrder = createSingleProductPaidOrder("ORD-20260624-0005", LocalDateTime.of(2026, 6, 11, 10, 0));
		persistPayment(canceledOrder, UUID.fromString("00000000-0000-0000-0000-000000000412"), PRODUCT_AMOUNT_2, LocalDateTime.of(2026, 6, 11, 10, 1));
		cancelOrderForTest(canceledOrder, LocalDateTime.of(2026, 6, 12, 10, 0));

		Order refundedOrder = createSingleProductPaidOrder("ORD-20260624-0006", LocalDateTime.of(2026, 6, 13, 10, 0));
		persistPayment(refundedOrder, UUID.fromString("00000000-0000-0000-0000-000000000413"), PRODUCT_AMOUNT_1, LocalDateTime.of(2026, 6, 13, 10, 1));
		refundedOrder.refund(LocalDateTime.of(2026, 6, 14, 10, 0));
		entityManager.flush();
		entityManager.clear();

		long actualAmount = adminOrderQueryRepository.sumMonthlyTransactionAmount(start, endExclusive);

		assertThat(actualAmount).isEqualTo(PRODUCT_AMOUNT_1);
	}

	@Test
	@DisplayName("부분 환불 주문은 환불된 주문상품 금액만 실제 거래액에서 차감한다")
	void sumMonthlyTransactionAmount_subtractsOnlyRefundedProductAmount() {
		LocalDateTime start = LocalDateTime.of(2026, 6, 1, 0, 0);
		LocalDateTime endExclusive = LocalDateTime.of(2026, 7, 1, 0, 0);
		Order order = createOrder("ORD-20260624-0010", OrderStatus.PAID, LocalDateTime.of(2026, 6, 15, 10, 0));
		OrderProduct refundedProduct = OrderProduct.create(
			PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_1
		);
		order.addOrderProduct(refundedProduct);
		order.addOrderProduct(OrderProduct.create(
			PRODUCT_ID_2, SELLER_ID_2, PRODUCT_TITLE_2, PRODUCT_TYPE_PROMPT, "Claude-3", PRODUCT_AMOUNT_2
		));
		order.markPaid(LocalDateTime.of(2026, 6, 15, 10, 1));
		entityManager.persist(order);
		entityManager.flush();
		persistPayment(order, UUID.fromString("00000000-0000-0000-0000-000000000417"),
			PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2, LocalDateTime.of(2026, 6, 15, 10, 1));
		order.requestRefundProducts(Set.of(refundedProduct.getId()));
		order.completeRefundProducts(Set.of(refundedProduct.getId()), LocalDateTime.of(2026, 6, 16, 10, 0));
		entityManager.flush();
		entityManager.clear();

		long actualAmount = adminOrderQueryRepository.sumMonthlyTransactionAmount(start, endExclusive);

		assertThat(actualAmount).isEqualTo(PRODUCT_AMOUNT_2);
	}

	@Test
	@DisplayName("최근 거래 추이는 일자별 승인 건수와 실제 거래액을 조회한다")
	void findDailyTransactions_success() {
		LocalDate day = LocalDate.of(2026, 6, 24);
		Order paidOrder = createSingleProductPaidOrder("ORD-20260624-0007", day.atTime(10, 0));
		persistPayment(paidOrder, UUID.fromString("00000000-0000-0000-0000-000000000414"), PRODUCT_AMOUNT_1, day.atTime(10, 1));
		Order canceledOrder = createSingleProductPaidOrder("ORD-20260624-0008", day.atTime(11, 0));
		persistPayment(canceledOrder, UUID.fromString("00000000-0000-0000-0000-000000000415"), PRODUCT_AMOUNT_2, day.atTime(11, 1));
		cancelOrderForTest(canceledOrder, day.atTime(12, 0));
		entityManager.flush();
		entityManager.clear();

		List<AdminDailyTransactionProjection> result = adminOrderQueryRepository.findDailyTransactions(
			day.atStartOfDay(),
			day.plusDays(1).atStartOfDay()
		);

		assertThat(result).hasSize(1);
		assertThat(result.getFirst().date()).isEqualTo(day);
		assertThat(result.getFirst().transactionCount()).isEqualTo(2L);
		assertThat(result.getFirst().transactionAmount()).isEqualTo(PRODUCT_AMOUNT_1);
	}

	@Test
	@DisplayName("최근 거래 추이는 취소일 기준으로 거래액을 차감한다")
	void findDailyTransactions_subtractsCanceledAmountByCanceledDate() {
		LocalDate approvedDay = LocalDate.of(2026, 6, 23);
		LocalDate canceledDay = LocalDate.of(2026, 6, 24);
		Order canceledOrder = createSingleProductPaidOrder("ORD-20260624-0009", approvedDay.atTime(11, 0));
		persistPayment(canceledOrder, UUID.fromString("00000000-0000-0000-0000-000000000416"), PRODUCT_AMOUNT_2, approvedDay.atTime(11, 1));
		cancelOrderForTest(canceledOrder, canceledDay.atTime(12, 0));
		entityManager.flush();
		entityManager.clear();

		List<AdminDailyTransactionProjection> result = adminOrderQueryRepository.findDailyTransactions(
			approvedDay.atStartOfDay(),
			canceledDay.plusDays(1).atStartOfDay()
		);

		assertThat(result).extracting(AdminDailyTransactionProjection::date)
			.containsExactly(approvedDay, canceledDay);
		assertThat(result.get(0).transactionCount()).isEqualTo(1L);
		assertThat(result.get(0).transactionAmount()).isEqualTo(PRODUCT_AMOUNT_2);
		assertThat(result.get(1).transactionCount()).isZero();
		assertThat(result.get(1).transactionAmount()).isEqualTo(-PRODUCT_AMOUNT_2);
	}

	@Test
	@DisplayName("최근 거래 추이는 부분 환불일에 환불된 주문상품 금액만 차감한다")
	void findDailyTransactions_subtractsRefundedProductAmountByRefundedDate() {
		LocalDate approvedDay = LocalDate.of(2026, 6, 23);
		LocalDate refundedDay = LocalDate.of(2026, 6, 24);
		Order order = createOrder("ORD-20260624-0011", OrderStatus.PAID, approvedDay.atTime(11, 0));
		OrderProduct refundedProduct = OrderProduct.create(
			PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_1
		);
		order.addOrderProduct(refundedProduct);
		order.addOrderProduct(OrderProduct.create(
			PRODUCT_ID_2, SELLER_ID_2, PRODUCT_TITLE_2, PRODUCT_TYPE_PROMPT, "Claude-3", PRODUCT_AMOUNT_2
		));
		order.markPaid(approvedDay.atTime(11, 1));
		entityManager.persist(order);
		entityManager.flush();
		persistPayment(order, UUID.fromString("00000000-0000-0000-0000-000000000418"),
			PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2, approvedDay.atTime(11, 1));
		order.requestRefundProducts(Set.of(refundedProduct.getId()));
		order.completeRefundProducts(Set.of(refundedProduct.getId()), refundedDay.atTime(12, 0));
		entityManager.flush();
		entityManager.clear();

		List<AdminDailyTransactionProjection> result = adminOrderQueryRepository.findDailyTransactions(
			approvedDay.atStartOfDay(),
			refundedDay.plusDays(1).atStartOfDay()
		);

		assertThat(result).extracting(AdminDailyTransactionProjection::date)
			.containsExactly(approvedDay, refundedDay);
		assertThat(result.getFirst().transactionAmount()).isEqualTo(PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
		assertThat(result.getLast().transactionAmount()).isEqualTo(-PRODUCT_AMOUNT_1);
	}

	private void cancelOrderForTest(Order order, LocalDateTime canceledAt) {
		ReflectionTestUtils.setField(order, "orderStatus", OrderStatus.CANCELED);
		ReflectionTestUtils.setField(order, "canceledAt", canceledAt);
		order.getOrderProducts().forEach(op -> {
			ReflectionTestUtils.setField(op, "orderProductStatus", OrderStatus.CANCELED);
			ReflectionTestUtils.setField(op, "canceledAt", canceledAt);
		});
		entityManager.persist(order);
	}

	private Order createSingleProductPaidOrder(String orderNumber, LocalDateTime createdAt) {
		Order order = createOrder(orderNumber, OrderStatus.PAID, createdAt);
		order.addOrderProduct(OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_1));
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

	private void persistPayment(Order order, UUID paymentId, int approvedAmount, LocalDateTime approvedAt) {
		entityManager.persist(OrderPayment.create(order.getId(), paymentId, BUYER_ID, approvedAmount, approvedAt));
	}
}
