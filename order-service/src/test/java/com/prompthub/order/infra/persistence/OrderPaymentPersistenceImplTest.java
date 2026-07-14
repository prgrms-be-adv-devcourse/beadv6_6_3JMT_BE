package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.dto.OrderPaymentListProjection;
import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import com.prompthub.order.infra.persistence.order.OrderPaymentPersistence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.Set;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_NUMBER;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TYPE_PROMPT;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_2;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_ITEM_COUNT;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
class OrderPaymentPersistenceImplTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private OrderPaymentPersistence orderPaymentPersistence;

	@Test
	@DisplayName("paymentId 기준으로 결제 내역 존재 여부를 확인한다")
	void existsByPaymentId_success() {
		Order order = Order.create(BUYER_ID, ORDER_NUMBER, TOTAL_AMOUNT, TOTAL_ITEM_COUNT);
		entityManager.persist(order);
		entityManager.persist(OrderPayment.create(
			order.getId(),
			PAYMENT_ID,
			BUYER_ID,
			TOTAL_AMOUNT,
			APPROVED_AT
		));
		entityManager.flush();
		entityManager.clear();

		assertThat(orderPaymentPersistence.existsByPaymentId(PAYMENT_ID)).isTrue();
		assertThat(orderPaymentPersistence.existsByPaymentId(UUID.fromString("00000000-0000-0000-0000-000000000999")))
			.isFalse();
	}

	@Test
	@DisplayName("구매자 결제 내역을 order, order_product, order_payment 조인으로 조회한다")
	void searchOrderPayments_join_success() {
		// given
		Order order = Order.create(BUYER_ID, ORDER_NUMBER, TOTAL_AMOUNT, TOTAL_ITEM_COUNT);
		OrderProduct orderProduct = OrderProduct.create(
			PRODUCT_ID_1,
			SELLER_ID_1,
			PRODUCT_TITLE_1,
			PRODUCT_TYPE_PROMPT,
			"GPT-4",
			PRODUCT_AMOUNT_1
		);
		order.addOrderProduct(orderProduct);
		order.markPaid(APPROVED_AT);

		entityManager.persist(order);
		entityManager.persist(OrderPayment.create(
			order.getId(),
			PAYMENT_ID,
			BUYER_ID,
			TOTAL_AMOUNT,
			APPROVED_AT
		));

		Order otherBuyerOrder = Order.create(
			UUID.fromString("00000000-0000-0000-0000-000000000991"),
			"ORD-20260619-9999",
			TOTAL_AMOUNT,
			TOTAL_ITEM_COUNT
		);
		otherBuyerOrder.addOrderProduct(OrderProduct.create(
			PRODUCT_ID_1,
			SELLER_ID_1,
			PRODUCT_TITLE_1,
			PRODUCT_TYPE_PROMPT,
			"GPT-4",
			PRODUCT_AMOUNT_1
		));
		otherBuyerOrder.markPaid(APPROVED_AT);
		entityManager.persist(otherBuyerOrder);
		entityManager.persist(OrderPayment.create(
			otherBuyerOrder.getId(),
			UUID.fromString("00000000-0000-0000-0000-000000000992"),
			otherBuyerOrder.getBuyerId(),
			TOTAL_AMOUNT,
			APPROVED_AT
		));

		entityManager.flush();
		entityManager.clear();

		PageRequest pageable = PageRequest.of(0, 20, Sort.by(
			Sort.Order.desc("approvedAt")
		));

		// when
		Page<OrderPaymentListProjection> result = orderPaymentPersistence.searchOrderPayments(BUYER_ID, pageable);

		// then
		assertThat(result.getTotalElements()).isEqualTo(1);
		assertThat(result.getContent()).hasSize(1);

		OrderPaymentListProjection projection = result.getContent().getFirst();
		assertThat(projection.orderId()).isEqualTo(order.getId());
		assertThat(projection.paymentId()).isEqualTo(PAYMENT_ID);
		assertThat(projection.orderStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(projection.isRefundable()).isTrue();
		assertThat(projection.productType()).isEqualTo(PRODUCT_TYPE_PROMPT);
		assertThat(projection.title()).isEqualTo(PRODUCT_TITLE_1);
		assertThat(projection.amount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(projection.paidAt()).isEqualTo(APPROVED_AT);
		assertThat(projection.approvedAt()).isEqualTo(APPROVED_AT);
	}

	@Test
	@DisplayName("다건 상품 결제는 결제 건 기준 1개 row로 조회한다")
	void searchOrderPayments_multiProductPayment_groupedByPayment_success() {
		// given
		Order order = Order.create(BUYER_ID, ORDER_NUMBER, TOTAL_AMOUNT, TOTAL_ITEM_COUNT);
		order.addOrderProduct(OrderProduct.create(
			PRODUCT_ID_1,
			SELLER_ID_1,
			PRODUCT_TITLE_1,
			PRODUCT_TYPE_PROMPT,
			"GPT-4",
			PRODUCT_AMOUNT_1
		));
		order.addOrderProduct(OrderProduct.create(
			PRODUCT_ID_2,
			SELLER_ID_2,
			PRODUCT_TITLE_2,
			PRODUCT_TYPE_PROMPT,
			"GPT-4",
			PRODUCT_AMOUNT_2
		));
		order.markPaid(APPROVED_AT);

		entityManager.persist(order);
		entityManager.persist(OrderPayment.create(
			order.getId(),
			PAYMENT_ID,
			BUYER_ID,
			TOTAL_AMOUNT,
			APPROVED_AT
		));

		entityManager.flush();
		entityManager.clear();

		PageRequest pageable = PageRequest.of(0, 1, Sort.by(
			Sort.Order.desc("approvedAt")
		));

		// when
		Page<OrderPaymentListProjection> result = orderPaymentPersistence.searchOrderPayments(BUYER_ID, pageable);

		// then
		assertThat(result.getTotalElements()).isEqualTo(1);
		assertThat(result.hasNext()).isFalse();
		assertThat(result.getContent()).hasSize(1);

		OrderPaymentListProjection projection = result.getContent().getFirst();
		assertThat(projection.orderId()).isEqualTo(order.getId());
		assertThat(projection.paymentId()).isEqualTo(PAYMENT_ID);
		assertThat(projection.title()).isIn(
			PRODUCT_TITLE_1 + " 외 1건",
			PRODUCT_TITLE_2 + " 외 1건"
		);
		assertThat(projection.amount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(projection.isRefundable()).isTrue();
	}

	@Test
	@DisplayName("부분 환불 결제는 다운로드하지 않은 PAID 상품이 남으면 환불 가능하다")
	void searchOrderPayments_partiallyRefundedWithPaidProduct_refundable() {
		Order order = Order.create(BUYER_ID, ORDER_NUMBER, TOTAL_AMOUNT, TOTAL_ITEM_COUNT);
		OrderProduct refundedProduct = OrderProduct.create(
			PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_1
		);
		OrderProduct paidProduct = OrderProduct.create(
			PRODUCT_ID_2, SELLER_ID_2, PRODUCT_TITLE_2, PRODUCT_TYPE_PROMPT, "GPT-4", PRODUCT_AMOUNT_2
		);
		order.addOrderProduct(refundedProduct);
		order.addOrderProduct(paidProduct);
		order.markPaid(APPROVED_AT);
		entityManager.persist(order);
		entityManager.flush();
		order.requestRefundProducts(Set.of(refundedProduct.getId()));
		order.completeRefundProducts(Set.of(refundedProduct.getId()), APPROVED_AT.plusDays(1));
		entityManager.persist(OrderPayment.create(order.getId(), PAYMENT_ID, BUYER_ID, TOTAL_AMOUNT, APPROVED_AT));
		entityManager.flush();
		entityManager.clear();

		Page<OrderPaymentListProjection> result = orderPaymentPersistence.searchOrderPayments(
			BUYER_ID,
			PageRequest.of(0, 20, Sort.by(Sort.Order.desc("approvedAt")))
		);

		assertThat(result.getContent().getFirst().orderStatus()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
		assertThat(result.getContent().getFirst().isRefundable()).isTrue();
	}
}
