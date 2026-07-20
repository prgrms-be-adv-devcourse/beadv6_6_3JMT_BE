package com.prompthub.payment.application.usecase;

import com.prompthub.payment.application.dto.command.ConfirmPaymentCommand;
import com.prompthub.payment.application.dto.result.PaymentResult;

public interface ConfirmPaymentUseCase {
    PaymentResult confirm(ConfirmPaymentCommand command);
}
