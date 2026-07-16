package com.prompthub.order.application.usecase;

import com.prompthub.order.application.dto.CreateOrderCommand;
import com.prompthub.order.application.dto.CreateOrderResult;

import java.util.UUID;

public interface CreateOrderUseCase {

	CreateOrderResult createOrder(UUID buyerId, CreateOrderCommand command);
}
