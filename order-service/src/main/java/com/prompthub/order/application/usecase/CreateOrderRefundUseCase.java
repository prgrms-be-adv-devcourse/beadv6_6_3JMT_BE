package com.prompthub.order.application.usecase;

import com.prompthub.order.application.dto.CreateOrderRefundCommand;
import com.prompthub.order.application.dto.OrderRefundResult;

public interface CreateOrderRefundUseCase {
    OrderRefundResult create(CreateOrderRefundCommand command);
}
