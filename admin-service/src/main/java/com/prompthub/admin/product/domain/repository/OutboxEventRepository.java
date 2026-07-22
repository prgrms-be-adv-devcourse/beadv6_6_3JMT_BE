package com.prompthub.admin.product.domain.repository;

import com.prompthub.admin.product.domain.model.entity.OutboxEvent;

public interface OutboxEventRepository {

	void append(OutboxEvent outboxEvent);
}
