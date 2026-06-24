package com.prompthub.paymentservice.application.usecase;

import com.prompthub.paymentservice.application.dto.command.RefundPaymentCommand;

public interface RefundPaymentUseCase {
    void refund(RefundPaymentCommand command);
}
