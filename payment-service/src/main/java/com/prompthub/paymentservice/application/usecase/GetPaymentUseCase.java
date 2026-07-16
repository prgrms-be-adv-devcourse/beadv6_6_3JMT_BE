package com.prompthub.paymentservice.application.usecase;

import com.prompthub.paymentservice.application.dto.command.GetPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentQueryResult;

/**
 * order-service가 Kafka 결제 이벤트(PAYMENT_APPROVED/PAYMENT_FAILED)를
 * 못 받았을 때 gRPC로 폴백 조회하는 단건 결제 조회.
 */
public interface GetPaymentUseCase {
    PaymentQueryResult getPayment(GetPaymentCommand command);
}
