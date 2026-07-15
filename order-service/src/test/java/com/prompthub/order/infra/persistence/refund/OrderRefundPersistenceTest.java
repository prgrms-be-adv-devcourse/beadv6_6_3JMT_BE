package com.prompthub.order.infra.persistence.refund;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
class OrderRefundPersistenceTest {

	private static final UUID ORDER_PRODUCT_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000601");
	private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 7, 15, 12, 0);
	private static final LocalDateTime NEXT_CHECK_AT = REQUESTED_AT.plusMinutes(10);

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private OrderRefundPersistence orderRefundPersistence;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("환불 요청과 단건 상세를 cascade 저장하고 ID로 함께 조회한다")
	void saveAndFindByIdWithProduct_success() {
		OrderRefund refund = orderRefundPersistence.saveAndFlush(createRefund(ORDER_PRODUCT_ID));
		entityManager.clear();

		OrderRefund found = orderRefundPersistence.findByIdWithProduct(refund.getId()).orElseThrow();

		assertThat(found.getId()).isEqualTo(refund.getId());
		assertThat(found.getProduct().getOrderProductId()).isEqualTo(ORDER_PRODUCT_ID);
		assertThat(found.getProduct().getRefundAmount()).isEqualTo(PRODUCT_AMOUNT_1);
	}

	@Test
	@DisplayName("paymentId와 orderProductId로 환불 요청과 상세를 조회한다")
	void findByPaymentIdAndOrderProductId_success() {
		OrderRefund refund = orderRefundPersistence.saveAndFlush(createRefund(ORDER_PRODUCT_ID));
		entityManager.clear();

		OrderRefund found = orderRefundPersistence
			.findByPaymentIdAndOrderProductId(PAYMENT_ID, ORDER_PRODUCT_ID)
			.orElseThrow();

		assertThat(found.getId()).isEqualTo(refund.getId());
		assertThat(found.getProduct().getOrderProductId()).isEqualTo(ORDER_PRODUCT_ID);
	}

	@Test
	@DisplayName("주문에 REQUESTED 환불이 존재하는지 확인한다")
	void existsByOrderIdAndStatus_success() {
		orderRefundPersistence.saveAndFlush(createRefund(ORDER_PRODUCT_ID));

		assertThat(orderRefundPersistence.existsByOrderIdAndStatus(ORDER_ID, OrderRefundStatus.REQUESTED)).isTrue();
		assertThat(orderRefundPersistence.existsByOrderIdAndStatus(ORDER_ID, OrderRefundStatus.COMPLETED)).isFalse();
	}

	@Test
	@DisplayName("같은 주문상품은 두 환불 요청에 저장할 수 없다")
	void save_sameOrderProductTwice_throwsException() {
		orderRefundPersistence.saveAndFlush(createRefund(ORDER_PRODUCT_ID));
		entityManager.clear();

		OrderRefund duplicated = OrderRefund.request(
			UUID.randomUUID(),
			UUID.randomUUID(),
			BUYER_ID,
			ORDER_PRODUCT_ID,
			PRODUCT_AMOUNT_1,
			REQUESTED_AT.plusMinutes(1),
			NEXT_CHECK_AT.plusMinutes(1)
		);

		assertThatThrownBy(() -> orderRefundPersistence.saveAndFlush(duplicated))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("하나의 환불 요청에는 상세를 두 건 저장할 수 없다")
	void insert_secondProductForSameRefund_throwsException() {
		OrderRefund refund = orderRefundPersistence.saveAndFlush(createRefund(ORDER_PRODUCT_ID));

		assertThatThrownBy(() -> jdbcTemplate.update("""
			insert into order_refund_product (
				id, order_refund_id, order_product_id, refund_amount, created_at
			) values (?, ?, ?, ?, ?)
			""",
			UUID.randomUUID(),
			refund.getId(),
			UUID.randomUUID(),
			PRODUCT_AMOUNT_1,
			REQUESTED_AT
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("환불 총액은 DB에서도 양수만 허용한다")
	void update_totalRefundAmountToZero_throwsException() {
		OrderRefund refund = orderRefundPersistence.saveAndFlush(createRefund(ORDER_PRODUCT_ID));

		assertThatThrownBy(() -> jdbcTemplate.update(
			"update order_refund set total_refund_amount = 0 where id = ?",
			refund.getId()
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("환불 상세 금액은 DB에서도 양수만 허용한다")
	void update_productRefundAmountToZero_throwsException() {
		OrderRefund refund = orderRefundPersistence.saveAndFlush(createRefund(ORDER_PRODUCT_ID));

		assertThatThrownBy(() -> jdbcTemplate.update(
			"update order_refund_product set refund_amount = 0 where order_refund_id = ?",
			refund.getId()
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("결과 확인 횟수는 DB에서도 음수를 허용하지 않는다")
	void update_checkCountToNegative_throwsException() {
		OrderRefund refund = orderRefundPersistence.saveAndFlush(createRefund(ORDER_PRODUCT_ID));

		assertThatThrownBy(() -> jdbcTemplate.update(
			"update order_refund set check_count = -1 where id = ?",
			refund.getId()
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	private OrderRefund createRefund(UUID orderProductId) {
		return OrderRefund.request(
			ORDER_ID,
			PAYMENT_ID,
			BUYER_ID,
			orderProductId,
			PRODUCT_AMOUNT_1,
			REQUESTED_AT,
			NEXT_CHECK_AT
		);
	}
}
