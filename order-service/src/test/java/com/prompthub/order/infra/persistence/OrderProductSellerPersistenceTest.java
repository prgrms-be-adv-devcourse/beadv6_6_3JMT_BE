package com.prompthub.order.infra.persistence;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_2;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_2;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_2;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
class OrderProductSellerPersistenceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsSellerIdForEachOrderProduct() {
        Order order = Order.create(BUYER_ID, "ORD-SNAPSHOT", PRODUCT_AMOUNT_1 + PRODUCT_AMOUNT_2);
        OrderProduct first = OrderProduct.create(PRODUCT_ID_1, SELLER_ID_1, PRODUCT_TITLE_1, PRODUCT_AMOUNT_1);
        OrderProduct second = OrderProduct.create(PRODUCT_ID_2, SELLER_ID_2, PRODUCT_TITLE_2, PRODUCT_AMOUNT_2);
        order.addOrderProduct(first);
        order.addOrderProduct(second);

        entityManager.persistAndFlush(order);
        entityManager.clear();

        Order reloaded = entityManager.find(Order.class, order.getId());

        assertThat(reloaded.getOrderProducts())
            .extracting(OrderProduct::getSellerId)
            .containsExactlyInAnyOrder(SELLER_ID_1, SELLER_ID_2);
    }
}
