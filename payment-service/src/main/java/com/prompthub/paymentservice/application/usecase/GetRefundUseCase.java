package com.prompthub.paymentservice.application.usecase;

import com.prompthub.paymentservice.application.dto.command.GetRefundCommand;
import com.prompthub.paymentservice.application.dto.result.RefundQueryResult;

/**
 * order-service가 Kafka 환불 이벤트(PAYMENT_REFUNDED/PAYMENT_REFUND_FAILED)를
 * 못 받았을 때 gRPC로 폴백 조회하는 단건 환불 조회.
 */
public interface GetRefundUseCase {
    RefundQueryResult getRefund(GetRefundCommand command);
}
