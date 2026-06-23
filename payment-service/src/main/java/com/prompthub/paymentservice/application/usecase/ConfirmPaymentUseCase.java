package com.prompthub.paymentservice.application.usecase;

import com.prompthub.paymentservice.application.dto.command.ConfirmPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentResult;

public interface ConfirmPaymentUseCase {
    PaymentResult confirm(ConfirmPaymentCommand command);
}
