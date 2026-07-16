package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.Test;

import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TITLE_1;
import static com.prompthub.order.fixture.OrderFixture.REFUNDED_AT;
import static com.prompthub.order.fixture.OrderFixture.createOrderProduct1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderProductTest {

    @Test
    void create_startsPending() {
        OrderProduct product = OrderProduct.create(PRODUCT_ID_1, PRODUCT_TITLE_1, PRODUCT_AMOUNT_1);

        assertThat(product.getId()).isNotNull();
        assertThat(product.getOrder()).isNull();
        assertThat(product.getProductId()).isEqualTo(PRODUCT_ID_1);
        assertThat(product.getProductTitle()).isEqualTo(PRODUCT_TITLE_1);
        assertThat(product.getProductAmount()).isEqualTo(PRODUCT_AMOUNT_1);
        assertThat(product.getOrderStatus()).isEqualTo(OrderProductStatus.PENDING);
        assertThat(product.isDownloaded()).isFalse();
        assertThat(product.getRefundedAt()).isNull();
    }

    @Test
    void failedProduct_canBecomePaidAfterPaymentRetry() {
        OrderProduct product = createOrderProduct1();
        product.markFailed();

        product.markPaid();

        assertThat(product.getOrderStatus()).isEqualTo(OrderProductStatus.PAID);
    }

    @Test
    void paidProduct_canBecomeRefunded() {
        OrderProduct product = createOrderProduct1();
        product.markPaid();

        product.refund(REFUNDED_AT);

        assertThat(product.getOrderStatus()).isEqualTo(OrderProductStatus.REFUNDED);
        assertThat(product.getRefundedAt()).isEqualTo(REFUNDED_AT);
    }

    @Test
    void pendingProduct_cannotBeRefunded() {
        OrderProduct product = createOrderProduct1();

        assertThatThrownBy(product::refund).isInstanceOf(OrderException.class);
    }

    @Test
    void onlyPaidProduct_canBeDownloaded() {
        OrderProduct pending = createOrderProduct1();
        assertThatThrownBy(pending::markDownloaded).isInstanceOf(OrderException.class);

        pending.markPaid();
        pending.markDownloaded();

        assertThat(pending.isDownloaded()).isTrue();
        assertThat(pending.isRefundable()).isFalse();
    }
}
