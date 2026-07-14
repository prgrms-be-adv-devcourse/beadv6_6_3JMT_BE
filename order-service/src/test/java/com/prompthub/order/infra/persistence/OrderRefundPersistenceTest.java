package com.prompthub.order.infra.persistence;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.model.OrderRefundProduct;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import com.prompthub.order.infra.persistence.refund.OrderRefundPersistence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_2;
import static com.prompthub.order.fixture.OrderRefundFixture.ORDER_PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderRefundFixture.ORDER_PRODUCT_ID_2;
import static com.prompthub.order.fixture.OrderRefundFixture.REQUESTED_AT;
import static com.prompthub.order.fixture.OrderRefundFixture.paidProduct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
class OrderRefundPersistenceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRefundPersistence orderRefundPersistence;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("환불 헤더와 두 주문상품 금액 스냅샷을 함께 저장하고 조회한다")
    void saveAndFind_refundWithTwoProducts() {
        OrderRefund refund = request(ORDER_ID, REQUESTED_AT);

        OrderRefund saved = orderRefundPersistence.save(refund);
        entityManager.flush();
        entityManager.clear();

        OrderRefund found = orderRefundPersistence.findById(saved.getId()).orElseThrow();

        assertThat(found.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(found.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(found.getBuyerId()).isEqualTo(BUYER_ID);
        assertThat(found.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
        assertThat(found.getTotalRefundAmount()).isEqualTo(PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
        assertThat(found.getProducts())
            .extracting(OrderRefundProduct::getOrderProductId, OrderRefundProduct::getRefundAmount)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(ORDER_PRODUCT_ID_1, PRODUCT_AMOUNT_1),
                org.assertj.core.groups.Tuple.tuple(ORDER_PRODUCT_ID_2, PRODUCT_AMOUNT_2)
            );
    }

    @Test
    @DisplayName("주문의 환불 이력을 상품과 함께 요청 시각 최신순으로 조회한다")
    void findAllByOrderIdWithProducts_ordersNewestFirst() {
        OrderRefund oldest = request(ORDER_ID, REQUESTED_AT);
        OrderRefund newest = request(ORDER_ID, REQUESTED_AT.plusMinutes(10));
        OrderRefund otherOrder = request(UUID.randomUUID(), REQUESTED_AT.plusMinutes(20));
        orderRefundPersistence.saveAll(List.of(oldest, newest, otherOrder));
        entityManager.flush();
        entityManager.clear();

        List<OrderRefund> found = orderRefundPersistence.findAllByOrderIdWithProducts(ORDER_ID);

        assertThat(found).extracting(OrderRefund::getId)
            .containsExactly(newest.getId(), oldest.getId());
        assertThat(found).allSatisfy(refund -> assertThat(refund.getProducts()).hasSize(2));
    }

    @Test
    @DisplayName("요청 시각이 같아도 환불 ID 내림차순으로 이력을 결정적으로 조회한다")
    void findAllByOrderIdWithProducts_sameRequestedAt_ordersByIdDescending() {
        OrderRefund lowerId = request(ORDER_ID, REQUESTED_AT);
        OrderRefund higherId = request(ORDER_ID, REQUESTED_AT);
        ReflectionTestUtils.setField(
            lowerId, "id", UUID.fromString("00000000-0000-0000-0000-000000000701")
        );
        ReflectionTestUtils.setField(
            higherId, "id", UUID.fromString("00000000-0000-0000-0000-000000000702")
        );
        orderRefundPersistence.saveAll(List.of(lowerId, higherId));
        entityManager.flush();
        entityManager.clear();

        List<OrderRefund> found = orderRefundPersistence.findAllByOrderIdWithProducts(ORDER_ID);

        assertThat(found).extracting(OrderRefund::getId)
            .containsExactly(higherId.getId(), lowerId.getId());
    }

    @Test
    @DisplayName("같은 환불과 주문상품 조합을 두 번 저장하면 DB 유일성 제약이 거부한다")
    void save_duplicateRefundProduct_violatesUniqueConstraint() {
        OrderRefund refund = request(ORDER_ID, REQUESTED_AT);
        orderRefundPersistence.saveAndFlush(refund);
        OrderRefundProduct original = refund.getProducts().getFirst();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into order_refund_product
                    (id, order_refund_id, order_product_id, refund_amount)
                values (?, ?, ?, ?)
                """,
                UUID.randomUUID(), refund.getId(),
                original.getOrderProductId(), original.getRefundAmount()
            ))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private OrderRefund request(UUID orderId, java.time.LocalDateTime requestedAt) {
        return OrderRefund.request(
            orderId, PAYMENT_ID, BUYER_ID,
            List.of(
                paidProduct(ORDER_PRODUCT_ID_1, PRODUCT_AMOUNT_1),
                paidProduct(ORDER_PRODUCT_ID_2, PRODUCT_AMOUNT_2)
            ), requestedAt
        );
    }
}
