package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.application.dto.SettleableLineResult;
import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.enums.SettlementLineType;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class, SettlementOrderQueryRepositoryImpl.class})
class SettlementOrderQueryRepositoryImplTest {

    private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID JULY_ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID JULY_SELLER_A_PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID JULY_SELLER_B_PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID OUTSIDE_PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000203");
    private static final UUID SELLER_A_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID SELLER_B_ID = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final LocalDateTime JULY_START = LocalDateTime.of(2026, 7, 1, 0, 0);
    private static final LocalDateTime AUGUST_START = LocalDateTime.of(2026, 8, 1, 0, 0);

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private SettlementOrderQueryRepositoryImpl repository;

    @Test
    void findSettleableLines_returnsProductSnapshotLinesWithinHalfOpenPeriodInDeterministicOrder() {
        Order julyOrder = Order.create(BUYER_ID, "ORD-20260701-0001", 30_000);
        setId(julyOrder, JULY_ORDER_ID);
        OrderProduct sellerAProduct = product(JULY_SELLER_A_PRODUCT_ID, SELLER_A_ID, 10_000);
        OrderProduct sellerBProduct = product(JULY_SELLER_B_PRODUCT_ID, SELLER_B_ID, 20_000);
        julyOrder.addOrderProduct(sellerAProduct);
        julyOrder.addOrderProduct(sellerBProduct);
        julyOrder.markCompleted(JULY_START);
        julyOrder.refundOrderProduct(JULY_SELLER_A_PRODUCT_ID, 10_000, JULY_START);

        Order outsideOrder = Order.create(BUYER_ID, "ORD-20260630-0001", 40_000);
        OrderProduct outsideProduct = product(OUTSIDE_PRODUCT_ID, SELLER_A_ID, 40_000);
        outsideOrder.addOrderProduct(outsideProduct);
        outsideOrder.markCompleted(JULY_START.minusSeconds(1));
        outsideOrder.refundOrderProduct(OUTSIDE_PRODUCT_ID, 40_000, AUGUST_START);

        entityManager.persist(julyOrder);
        entityManager.persist(outsideOrder);
        entityManager.flush();
        entityManager.clear();

        List<SettleableLineResult> result = repository.findSettleableLines(JULY_START, AUGUST_START);

        assertThat(result).containsExactly(
            new SettleableLineResult(
                SettlementLineType.PAID,
                JULY_ORDER_ID,
                JULY_SELLER_A_PRODUCT_ID,
                SELLER_A_ID,
                10_000,
                JULY_START
            ),
            new SettleableLineResult(
                SettlementLineType.REFUND,
                JULY_ORDER_ID,
                JULY_SELLER_A_PRODUCT_ID,
                SELLER_A_ID,
                10_000,
                JULY_START
            ),
            new SettleableLineResult(
                SettlementLineType.PAID,
                JULY_ORDER_ID,
                JULY_SELLER_B_PRODUCT_ID,
                SELLER_B_ID,
                20_000,
                JULY_START
            )
        );
    }

    @Test
    void findSettleableLines_sortsByOccurredAtThenOrderProductIdThenLineType() {
        UUID earlyOrderId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        UUID laterOrderId = UUID.fromString("00000000-0000-0000-0000-000000000103");
        UUID laterFirstProductId = UUID.fromString("00000000-0000-0000-0000-000000000200");
        UUID laterSecondProductId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID earlyProductId = UUID.fromString("00000000-0000-0000-0000-000000000202");
        LocalDateTime earlyOccurredAt = JULY_START.plusDays(1);
        LocalDateTime laterOccurredAt = JULY_START.plusDays(2);

        Order laterOrder = Order.create(BUYER_ID, "ORD-SORT-LATER", 30_000);
        setId(laterOrder, laterOrderId);
        laterOrder.addOrderProduct(product(laterFirstProductId, SELLER_A_ID, 10_000));
        laterOrder.addOrderProduct(product(laterSecondProductId, SELLER_B_ID, 20_000));
        laterOrder.markCompleted(laterOccurredAt);
        laterOrder.refundOrderProduct(laterFirstProductId, 10_000, laterOccurredAt);

        Order earlyOrder = Order.create(BUYER_ID, "ORD-SORT-EARLY", 40_000);
        setId(earlyOrder, earlyOrderId);
        earlyOrder.addOrderProduct(product(earlyProductId, SELLER_A_ID, 40_000));
        earlyOrder.markCompleted(earlyOccurredAt);

        entityManager.persist(laterOrder);
        entityManager.persist(earlyOrder);
        entityManager.flush();
        entityManager.clear();

        List<SettleableLineResult> result = repository.findSettleableLines(JULY_START, AUGUST_START);

        assertThat(result).containsExactly(
            line(SettlementLineType.PAID, earlyOrderId, earlyProductId, SELLER_A_ID, 40_000, earlyOccurredAt),
            line(SettlementLineType.PAID, laterOrderId, laterFirstProductId, SELLER_A_ID, 10_000, laterOccurredAt),
            line(SettlementLineType.REFUND, laterOrderId, laterFirstProductId, SELLER_A_ID, 10_000, laterOccurredAt),
            line(SettlementLineType.PAID, laterOrderId, laterSecondProductId, SELLER_B_ID, 20_000, laterOccurredAt)
        );
    }

	@Test
	void findSettleableLines_includesCompletedFreeProductAsZeroPaidLine() {
		UUID freeOrderId = UUID.fromString("00000000-0000-0000-0000-000000000104");
		UUID freeProductId = UUID.fromString("00000000-0000-0000-0000-000000000204");
		LocalDateTime completedAt = JULY_START.plusDays(3);
		Order freeOrder = Order.create(BUYER_ID, "ORD-FREE", 0);
		setId(freeOrder, freeOrderId);
		freeOrder.addOrderProduct(product(freeProductId, SELLER_A_ID, 0));
		freeOrder.markCompleted(completedAt);

		entityManager.persist(freeOrder);
		entityManager.flush();
		entityManager.clear();

		assertThat(repository.findSettleableLines(JULY_START, AUGUST_START))
			.containsExactly(line(
				SettlementLineType.PAID,
				freeOrderId,
				freeProductId,
				SELLER_A_ID,
				0,
				completedAt
			));
	}

    private OrderProduct product(UUID orderProductId, UUID sellerId, int amount) {
        OrderProduct product = OrderProduct.create(UUID.randomUUID(), sellerId, "상품 " + orderProductId, amount);
        setId(product, orderProductId);
        return product;
    }

    private SettleableLineResult line(
        SettlementLineType lineType,
        UUID orderId,
        UUID orderProductId,
        UUID sellerId,
        long lineAmount,
        LocalDateTime occurredAt
    ) {
        return new SettleableLineResult(
            lineType,
            orderId,
            orderProductId,
            sellerId,
            lineAmount,
            occurredAt
        );
    }

    private void setId(Object target, UUID id) {
        ReflectionTestUtils.setField(target, "id", id);
    }
}
