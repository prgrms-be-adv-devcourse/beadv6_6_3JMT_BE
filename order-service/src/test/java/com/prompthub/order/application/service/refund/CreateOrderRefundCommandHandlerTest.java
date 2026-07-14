package com.prompthub.order.application.service.refund;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.dto.CreateOrderRefundCommand;
import com.prompthub.order.application.dto.OrderRefundResult;
import com.prompthub.order.application.port.RefundMetricsPort;
import com.prompthub.order.application.service.event.order.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.order.OrderPolicyService;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.RefundRequestedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.*;
import static com.prompthub.order.fixture.OrderRefundFixture.REQUESTED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CreateOrderRefundCommandHandlerTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderPaymentRepository orderPaymentRepository;
    @Mock OrderRefundRepository orderRefundRepository;
    @Mock OrderRefundPolicy orderRefundPolicy;
    @Mock OutboxEventAppender outboxEventAppender;
    @Mock OrderEventMessageFactory orderEventMessageFactory;
    @Mock RefundMetricsPort refundMetrics;

    private CreateOrderRefundCommandHandler handler;
    private Order order;
    private OrderPayment payment;
    private List<UUID> requestedIds;

    @BeforeEach
    void setUp() {
        handler = new CreateOrderRefundCommandHandler(
            orderRepository, orderPaymentRepository, orderRefundRepository,
            new OrderPolicyService(), orderRefundPolicy, outboxEventAppender,
            orderEventMessageFactory, refundMetrics,
            Clock.fixed(REQUESTED_AT.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"))
        );
        order = createPaidOrderWithProducts();
        payment = OrderPayment.create(order.getId(), PAYMENT_ID, BUYER_ID, TOTAL_AMOUNT, APPROVED_AT);
        requestedIds = order.getOrderProducts().stream().map(OrderProduct::getId).toList();
    }

    @Test
    void newTwoProductRequest_savesSnapshotAndAppendsOnce() {
        givenValidRelations();
        given(orderRefundRepository.findAllByOrderIdWithProducts(order.getId())).willReturn(List.of());
        given(orderRefundPolicy.resolve(PAYMENT_ID, Set.copyOf(requestedIds), List.of())).willReturn(Optional.empty());
        EventMessage<RefundRequestedPayload> message = new EventMessage<>(
            UUID.randomUUID(), "REFUND_REQUESTED", REQUESTED_AT, "ORDER_REFUND", UUID.randomUUID(), null
        );
        given(orderEventMessageFactory.createRefundRequestedMessage(any())).willReturn(message);

        OrderRefundResult result = handler.create(command(BUYER_ID, PAYMENT_ID, requestedIds));

        ArgumentCaptor<OrderRefund> refundCaptor = ArgumentCaptor.forClass(OrderRefund.class);
        then(orderRefundRepository).should().save(refundCaptor.capture());
        then(outboxEventAppender).should().append(any());
        then(refundMetrics).should().recordRequested();
        assertThat(order.getOrderProducts())
            .filteredOn(product -> requestedIds.contains(product.getId()))
            .allMatch(product -> product.getOrderProductStatus() == OrderStatus.REFUND_REQUESTED);
        assertThat(refundCaptor.getValue().getTotalRefundAmount()).isEqualTo(TOTAL_AMOUNT);
        assertThat(refundCaptor.getValue().getProducts()).extracting("refundAmount")
            .containsExactlyInAnyOrder(PRODUCT_AMOUNT_1, PRODUCT_AMOUNT_2);
        assertThat(result.requestedAt()).isEqualTo(REQUESTED_AT);
    }

    @Test
    void relationFailures_doNotMutateOrWrite() {
        assertFailureWithoutMutation(command(UUID.randomUUID(), PAYMENT_ID, requestedIds),
            ErrorCode.FORBIDDEN, Optional.of(order), Optional.of(payment));
        org.mockito.Mockito.reset(orderRepository, orderPaymentRepository, orderRefundRepository, outboxEventAppender);
        assertFailureWithoutMutation(command(BUYER_ID, UUID.randomUUID(), requestedIds),
            ErrorCode.ORDER_PAYMENT_NOT_FOUND, Optional.of(order), Optional.empty());
    }

    @Test
    void paymentBuyerMismatch_failsBeforeMutation() {
        OrderPayment otherBuyerPayment = OrderPayment.create(
            order.getId(), PAYMENT_ID, UUID.randomUUID(), TOTAL_AMOUNT, APPROVED_AT
        );
        assertFailureWithoutMutation(command(BUYER_ID, PAYMENT_ID, requestedIds),
            ErrorCode.ORDER_REFUND_RELATION_MISMATCH, Optional.of(order), Optional.of(otherBuyerPayment));
    }

    @Test
    void missingDownloadedAndZeroAmountProduct_failAtomically() {
        givenValidRelations();
        given(orderRefundRepository.findAllByOrderIdWithProducts(order.getId())).willReturn(List.of());
        given(orderRefundPolicy.resolve(any(), any(), any())).willReturn(Optional.empty());

        assertCreateError(command(BUYER_ID, PAYMENT_ID, List.of(UUID.randomUUID())),
            ErrorCode.ORDER_REFUND_RELATION_MISMATCH);

        order.getOrderProducts().getFirst().markDownloaded();
        assertCreateError(command(BUYER_ID, PAYMENT_ID, List.of(order.getOrderProducts().getFirst().getId())),
            ErrorCode.ORDER_PRODUCT_ALREADY_DOWNLOADED);

        Order zeroOrder = createPaidOrderWithProducts();
        ReflectionTestUtils.setField(zeroOrder.getOrderProducts().getFirst(), "productAmount", 0);
        order = zeroOrder;
        payment = OrderPayment.create(order.getId(), PAYMENT_ID, BUYER_ID, TOTAL_AMOUNT, APPROVED_AT);
        givenValidRelations();
        assertCreateError(command(BUYER_ID, PAYMENT_ID, List.of(order.getOrderProducts().getFirst().getId())),
            ErrorCode.ORDER_PRODUCT_REFUND_NOT_ALLOWED);

        then(orderRefundRepository).should(never()).save(any());
        then(outboxEventAppender).should(never()).append(any());
        then(refundMetrics).shouldHaveNoInteractions();
    }

    @Test
    void reusableExactActiveOrCompleted_returnsWithoutMutationOrOutbox() {
        givenValidRelations();
        OrderRefund reusable = OrderRefund.request(
            order.getId(), PAYMENT_ID, BUYER_ID, List.of(order.getOrderProducts().getFirst()), REQUESTED_AT
        );
        given(orderRefundRepository.findAllByOrderIdWithProducts(order.getId())).willReturn(List.of(reusable));
        given(orderRefundPolicy.resolve(PAYMENT_ID, Set.of(requestedIds.getFirst()), List.of(reusable)))
            .willReturn(Optional.of(reusable));

        OrderRefundResult result = handler.create(command(BUYER_ID, PAYMENT_ID, List.of(requestedIds.getFirst())));

        assertThat(result.refundRequestId()).isEqualTo(reusable.getId());
        assertThat(order.getOrderProducts().getFirst().getOrderProductStatus()).isEqualTo(OrderStatus.PAID);
        then(orderRefundRepository).should(never()).save(any());
        then(outboxEventAppender).should(never()).append(any());
    }

    @Test
    void overlapPolicyErrors_doNotMutateOrWrite() {
        for (ErrorCode code : List.of(
            ErrorCode.ORDER_REFUND_IN_PROGRESS,
            ErrorCode.ORDER_REFUND_RESULT_UNKNOWN,
            ErrorCode.ORDER_REFUND_RETRY_NOT_ALLOWED
        )) {
            org.mockito.Mockito.reset(orderRefundPolicy, orderRefundRepository, orderRepository, orderPaymentRepository);
            givenValidRelations();
            given(orderRefundRepository.findAllByOrderIdWithProducts(order.getId())).willReturn(List.of());
            given(orderRefundPolicy.resolve(any(), any(), any())).willThrow(new OrderException(code));

            assertCreateError(command(BUYER_ID, PAYMENT_ID, requestedIds), code);
            assertThat(order.getOrderProducts()).allMatch(p -> p.getOrderProductStatus() == OrderStatus.PAID);
            then(orderRefundRepository).should(never()).save(any());
            then(outboxEventAppender).should(never()).append(any());
        }
    }

    @Test
    void invalidLists_areRejectedBeforeRepositoryAccess() {
        assertCreateError(command(BUYER_ID, PAYMENT_ID, null), ErrorCode.INVALID_INPUT_VALUE);
        assertCreateError(command(BUYER_ID, PAYMENT_ID, List.of()), ErrorCode.INVALID_INPUT_VALUE);
        assertCreateError(command(BUYER_ID, PAYMENT_ID, java.util.Arrays.asList(requestedIds.getFirst(), null)),
            ErrorCode.INVALID_INPUT_VALUE);
        assertCreateError(command(BUYER_ID, PAYMENT_ID, List.of(requestedIds.getFirst(), requestedIds.getFirst())),
            ErrorCode.INVALID_INPUT_VALUE);
        then(orderRepository).shouldHaveNoInteractions();
    }

    @Test
    void commandDefensivelyCopiesProductIdsButPreservesInvalidValuesForHandlerValidation() {
        List<UUID> mutableIds = new ArrayList<>(requestedIds);
        CreateOrderRefundCommand command = command(BUYER_ID, PAYMENT_ID, mutableIds);

        mutableIds.clear();

        assertThat(command.orderProductIds()).containsExactlyElementsOf(requestedIds);
        assertThatThrownBy(() -> command.orderProductIds().add(UUID.randomUUID()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(command(BUYER_ID, PAYMENT_ID, null).orderProductIds()).isNull();
        assertThat(command(BUYER_ID, PAYMENT_ID, java.util.Arrays.asList(requestedIds.getFirst(), null))
            .orderProductIds()).containsExactly(requestedIds.getFirst(), null);
    }

    private void givenValidRelations() {
        given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));
        given(orderPaymentRepository.findByOrderIdAndPaymentId(order.getId(), PAYMENT_ID)).willReturn(Optional.of(payment));
    }

    private CreateOrderRefundCommand command(UUID buyerId, UUID paymentId, List<UUID> productIds) {
        return new CreateOrderRefundCommand(buyerId, order.getId(), paymentId, productIds);
    }

    private void assertFailureWithoutMutation(
        CreateOrderRefundCommand command, ErrorCode code, Optional<Order> foundOrder, Optional<OrderPayment> foundPayment
    ) {
        given(orderRepository.findByIdWithOrderProductsForUpdate(command.orderId())).willReturn(foundOrder);
        foundOrder.filter(value -> value.getBuyerId().equals(command.buyerId()))
            .ifPresent(value -> given(orderPaymentRepository.findByOrderIdAndPaymentId(value.getId(), command.paymentId()))
                .willReturn(foundPayment));
        assertCreateError(command, code);
        assertThat(order.getOrderProducts()).allMatch(p -> p.getOrderProductStatus() == OrderStatus.PAID);
        then(orderRefundRepository).should(never()).save(any());
        then(outboxEventAppender).should(never()).append(any());
    }

    private void assertCreateError(CreateOrderRefundCommand command, ErrorCode code) {
        assertThatThrownBy(() -> handler.create(command))
            .isInstanceOf(OrderException.class)
            .satisfies(exception -> assertThat(((OrderException) exception).getErrorCode()).isEqualTo(code));
    }
}
