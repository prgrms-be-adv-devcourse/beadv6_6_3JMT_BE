package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.dto.RefundFailureCommand;
import com.prompthub.order.application.port.RefundMetricsPort;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderRefundFixture.FAILED_AT;
import static com.prompthub.order.fixture.OrderRefundFixture.REFUND_REQUEST_ID;
import static com.prompthub.order.fixture.OrderRefundFixture.REQUESTED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderRefundFailureServiceTest {

    @Mock OrderRefundRepository refundRepository;
    @Mock OrderRepository orderRepository;
    @Mock RefundMetricsPort refundMetrics;

    private OrderRefundFailureService failureService;
    private Order order;
    private OrderRefund refund;
    private RefundFailureCommand command;

    @BeforeEach
    void setUp() {
        order = createPaidOrderWithProducts();
        List<OrderProduct> targets = order.getOrderProducts();
        order.requestRefundProducts(targets.stream().map(OrderProduct::getId).collect(java.util.stream.Collectors.toSet()));
        refund = OrderRefund.request(order.getId(), PAYMENT_ID, order.getBuyerId(), targets, REQUESTED_AT);
        ReflectionTestUtils.setField(refund, "id", REFUND_REQUEST_ID);
        command = new RefundFailureCommand(
            REFUND_REQUEST_ID, PAYMENT_ID, order.getId(), refund.getTotalRefundAmount(),
            "PG_REJECTED", "declined", false, FAILED_AT
        );
        failureService = new OrderRefundFailureService(
            new OrderRefundResultContextLoader(refundRepository, orderRepository), refundMetrics
        );
    }

    @Test
    void fail_storesFailureAndRestoresEveryTargetWithoutSettlementEvent() {
        givenContext();

        failureService.fail(command);

        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.FAILED);
        assertThat(refund.getFailureCode()).isEqualTo("PG_REJECTED");
        assertThat(refund.getFailureReason()).isEqualTo("declined");
        assertThat(refund.isRetryable()).isFalse();
        assertThat(refund.getFailedAt()).isEqualTo(FAILED_AT);
        assertThat(order.getOrderStatus()).isEqualTo(com.prompthub.order.domain.enums.OrderStatus.PAID);
        assertThat(order.getOrderProducts()).allMatch(
            product -> product.getOrderProductStatus() == OrderStatus.PAID
        );
        then(refundMetrics).should().recordFailed(false);
    }

    @Test
    void fail_exactTerminalReplayIsNoOp() {
        givenContext();
        refund.fail("PG_REJECTED", "declined", false, FAILED_AT);
        order.restoreRefundProducts(refund.productIds());

        failureService.fail(command);

        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.FAILED);
        assertThat(refund.getFailureCode()).isEqualTo("PG_REJECTED");
        assertThat(refund.getFailureReason()).isEqualTo("declined");
        assertThat(refund.isRetryable()).isFalse();
        assertThat(refund.getFailedAt()).isEqualTo(FAILED_AT);
        assertThat(order.getOrderStatus()).isEqualTo(com.prompthub.order.domain.enums.OrderStatus.PAID);
        assertThat(order.getOrderProducts()).allMatch(
            product -> product.getOrderProductStatus() == OrderStatus.PAID
        );
    }

    @Test
    void fail_conflictingOrOppositeTerminalResultIsRejectedWithoutProductMutation() {
        givenContext();
        refund.complete(FAILED_AT);

        assertThatThrownBy(() -> failureService.fail(command)).isInstanceOf(IllegalStateException.class);
        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.COMPLETED);
        assertThat(refund.getCompletedAt()).isEqualTo(FAILED_AT);
        assertThat(order.getOrderProducts()).allMatch(
            product -> product.getOrderProductStatus() == OrderStatus.REFUND_REQUESTED
        );
    }

    private void givenContext() {
        given(refundRepository.findByIdForUpdate(REFUND_REQUEST_ID)).willReturn(Optional.of(refund));
        given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));
    }
}
