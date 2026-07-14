package com.prompthub.order.fixture;

import com.prompthub.order.domain.model.OrderProduct;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.PRODUCT_MODEL;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_TYPE_PROMPT;

public final class OrderRefundFixture {

    public static final UUID REFUND_REQUEST_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000701");
    public static final UUID ORDER_PRODUCT_ID_1 =
        UUID.fromString("00000000-0000-0000-0000-000000000601");
    public static final UUID ORDER_PRODUCT_ID_2 =
        UUID.fromString("00000000-0000-0000-0000-000000000602");
    public static final LocalDateTime REQUESTED_AT =
        LocalDateTime.of(2026, 7, 14, 10, 0);
    public static final LocalDateTime COMPLETED_AT = REQUESTED_AT.plusMinutes(1);
    public static final LocalDateTime FAILED_AT = REQUESTED_AT.plusMinutes(1);

    private OrderRefundFixture() {
    }

    public static OrderProduct paidProduct(UUID orderProductId, int amount) {
        OrderProduct product = OrderProduct.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "환불 상품",
            PRODUCT_TYPE_PROMPT,
            PRODUCT_MODEL,
            amount
        );
        ReflectionTestUtils.setField(product, "id", orderProductId);
        product.markPaid();
        return product;
    }
}
