package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.dto.CreateOrderRefundCommand;
import com.prompthub.order.application.dto.OrderRefundResult;
import com.prompthub.order.application.port.RefundMetricsPort;
import com.prompthub.order.application.service.event.order.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.order.OrderPolicyService;
import com.prompthub.order.application.usecase.CreateOrderRefundUseCase;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CreateOrderRefundCommandHandler implements CreateOrderRefundUseCase {

    private final OrderRepository orderRepository;
    private final OrderPaymentRepository orderPaymentRepository;
    private final OrderRefundRepository orderRefundRepository;
    private final OrderPolicyService orderPolicyService;
    private final OrderRefundPolicy orderRefundPolicy;
    private final OutboxEventAppender outboxEventAppender;
    private final OrderEventMessageFactory orderEventMessageFactory;
    private final RefundMetricsPort refundMetrics;
    private final Clock clock;

    @Override
    @Transactional
    public OrderRefundResult create(CreateOrderRefundCommand command) {
        validateCommand(command);
        Set<java.util.UUID> productIds = orderPolicyService.validateUniqueProductIds(command.orderProductIds());
        Order order = orderRepository.findByIdWithOrderProductsForUpdate(command.orderId())
            .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getBuyerId().equals(command.buyerId())) {
            throw new OrderException(ErrorCode.FORBIDDEN);
        }
        OrderPayment payment = orderPaymentRepository
            .findByOrderIdAndPaymentId(order.getId(), command.paymentId())
            .orElseThrow(() -> new OrderException(ErrorCode.ORDER_PAYMENT_NOT_FOUND));
        if (!payment.getBuyerId().equals(command.buyerId())) {
            throw new OrderException(ErrorCode.ORDER_REFUND_RELATION_MISMATCH);
        }

        Optional<OrderRefund> reusable = orderRefundPolicy.resolve(
            payment.getPaymentId(),
            productIds,
            orderRefundRepository.findAllByOrderIdWithProducts(order.getId())
        );
        if (reusable.isPresent()) {
            return OrderRefundResult.from(reusable.get());
        }

        List<OrderProduct> products = order.requestRefundProducts(productIds);
        OrderRefund refund = OrderRefund.request(
            order.getId(),
            payment.getPaymentId(),
            command.buyerId(),
            products,
            LocalDateTime.now(clock)
        );
        orderRefundRepository.save(refund);
        outboxEventAppender.append(orderEventMessageFactory.createRefundRequestedMessage(refund));
        refundMetrics.recordRequested();
        return OrderRefundResult.from(refund);
    }

    private void validateCommand(CreateOrderRefundCommand command) {
        if (command == null
            || command.buyerId() == null
            || command.orderId() == null
            || command.paymentId() == null) {
            throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
