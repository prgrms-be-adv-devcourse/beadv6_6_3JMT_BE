package com.prompthub.order.infra.persistence.config;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.QOrder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
class QuerydslConfigTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JPAQueryFactory queryFactory;

    @Test
    @DisplayName("QueryDSL 설정이 정상 작동하며, JPAQueryFactory를 이용해 주문을 조회할 수 있다.")
    void querydslSelectTest() {
        // given
        UUID buyerId = UUID.randomUUID();
		Order order = Order.create(buyerId, "ORD-20260622-0001", 10000);
        entityManager.persist(order);
        entityManager.flush();
        entityManager.clear();

        // when
        QOrder qOrder = QOrder.order;
        List<Order> result = queryFactory
                .selectFrom(qOrder)
                .where(qOrder.buyerId.eq(buyerId))
                .fetch();

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrderNumber()).isEqualTo("ORD-20260622-0001");
    }
}
