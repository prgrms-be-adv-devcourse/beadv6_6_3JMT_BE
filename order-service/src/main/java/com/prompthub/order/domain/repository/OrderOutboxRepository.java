package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.model.OrderOutbox;

public interface OrderOutboxRepository {

	OrderOutbox save(OrderOutbox orderOutbox);
}
