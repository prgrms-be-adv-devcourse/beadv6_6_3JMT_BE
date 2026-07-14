package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.dto.RefundCompletionCommand;
import com.prompthub.order.application.port.OrderRefundCompletedEventPort;
import com.prompthub.order.application.port.RefundMetricsPort;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
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
import static com.prompthub.order.fixture.OrderRefundFixture.COMPLETED_AT;
import static com.prompthub.order.fixture.OrderRefundFixture.REFUND_REQUEST_ID;
import static com.prompthub.order.fixture.OrderRefundFixture.REQUESTED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderRefundCompletionServiceTest {

    @Mock OrderRefundRepository refundRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderRefundCompletedEventPort completedEventPort;
    @Mock RefundMetricsPort refundMetrics;

    private OrderRefundCompletionService completionService;
    private Order order;
    private OrderRefund refund;
    private RefundCompletionCommand command;

    @BeforeEach
    void setUp() {
        order = createPaidOrderWithProducts();
        List<OrderProduct> targets = List.of(order.getOrderProducts().getFirst());
        order.requestRefundProducts(targets.stream().map(OrderProduct::getId).collect(java.util.stream.Collectors.toSet()));
        refund = OrderRefund.request(order.getId(), PAYMENT_ID, order.getBuyerId(), targets, REQUESTED_AT);
        ReflectionTestUtils.setField(refund, "id", REFUND_REQUEST_ID);
        command = new RefundCompletionCommand(
            REFUND_REQUEST_ID, PAYMENT_ID, order.getId(), refund.getTotalRefundAmount(), COMPLETED_AT
        );
        completionService = new OrderRefundCompletionService(
            new OrderRefundResultContextLoader(refundRepository, orderRepository), completedEventPort, refundMetrics
        );
    }

    @Test
    void complete_updatesRefundAndOnlyTargetProductsAndEmitsAuthoritativePayload() {
        givenContext();
        completionService.complete(command);

        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.COMPLETED);
        assertThat(order.getOrderProducts().getFirst().getOrderProductStatus())
            .isEqualTo(OrderStatus.REFUNDED);
        assertThat(order.getOrderProducts().get(1).getOrderProductStatus())
            .isEqualTo(OrderStatus.PAID);
        then(completedEventPort).should().emit(refund, order, COMPLETED_AT);
        then(refundMetrics).should().recordCompleted();
    }

    @Test
    void complete_mismatchedAmountRejectsBeforeOrderLoadOrMutation() {
        given(refundRepository.findByIdForUpdate(REFUND_REQUEST_ID)).willReturn(Optional.of(refund));
        RefundCompletionCommand mismatch = new RefundCompletionCommand(
            REFUND_REQUEST_ID, PAYMENT_ID, order.getId(), command.totalRefundAmount() + 1, COMPLETED_AT
        );

        assertThatThrownBy(() -> completionService.complete(mismatch))
            .isInstanceOfSatisfying(OrderException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_REFUND_EVENT_MISMATCH));

        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
        assertThat(order.getOrderProducts().getFirst().getOrderProductStatus())
            .isEqualTo(OrderStatus.REFUND_REQUESTED);
        assertThat(order.getOrderProducts().get(1).getOrderProductStatus())
            .isEqualTo(OrderStatus.PAID);
        then(orderRepository).should(never()).findByIdWithOrderProductsForUpdate(any());
        then(completedEventPort).shouldHaveNoInteractions();
        then(refundMetrics).shouldHaveNoInteractions();
    }

    @Test
    void complete_exactTerminalReplayIsNoOpWithoutDuplicateEvent() {
        givenContext();
        refund.complete(COMPLETED_AT);
        order.completeRefundProducts(refund.productIds(), COMPLETED_AT);

        completionService.complete(command);

        then(completedEventPort).shouldHaveNoInteractions();
    }

    @Test
    void complete_conflictingOrOppositeTerminalResultIsRejected() {
        givenContext();
        refund.fail("PG", "failed", false, COMPLETED_AT);

        assertThatThrownBy(() -> completionService.complete(command)).isInstanceOf(IllegalStateException.class);
        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.FAILED);
        assertThat(order.getOrderProducts().getFirst().getOrderProductStatus())
            .isEqualTo(OrderStatus.REFUND_REQUESTED);
        assertThat(order.getOrderProducts().get(1).getOrderProductStatus())
            .isEqualTo(OrderStatus.PAID);
        then(completedEventPort).shouldHaveNoInteractions();
    }

    @Test
    void complete_conflictingCompletedReplayPreservesCompletedStateAndProductsWithoutEvent() {
        givenContext();
        refund.complete(COMPLETED_AT);
        order.completeRefundProducts(refund.productIds(), COMPLETED_AT);
        RefundCompletionCommand conflict = new RefundCompletionCommand(
            command.refundRequestId(), command.paymentId(), command.orderId(),
            command.totalRefundAmount(), COMPLETED_AT.plusSeconds(1)
        );

        assertThatThrownBy(() -> completionService.complete(conflict))
            .isInstanceOf(IllegalStateException.class);

        assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.COMPLETED);
        assertThat(refund.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(order.getOrderProducts().getFirst().getOrderProductStatus())
            .isEqualTo(OrderStatus.REFUNDED);
        assertThat(order.getOrderProducts().getFirst().getRefundedAt()).isEqualTo(COMPLETED_AT);
        assertThat(order.getOrderProducts().get(1).getOrderProductStatus())
            .isEqualTo(OrderStatus.PAID);
        then(completedEventPort).shouldHaveNoInteractions();
    }

    private void givenContext() {
        given(refundRepository.findByIdForUpdate(REFUND_REQUEST_ID)).willReturn(Optional.of(refund));
        given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));
    }
}
